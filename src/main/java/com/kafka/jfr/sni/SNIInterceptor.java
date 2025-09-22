package com.kafka.jfr.sni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts SSL handshakes to capture SNI values
 */
public class SNIInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SNIInterceptor.class);
    private static final ConcurrentHashMap<String, String> sniMappings = new ConcurrentHashMap<>();

    /**
     * Called when SSLEngine.setSSLParameters is invoked
     */
    public static void onSetSSLParameters(SSLEngine engine, SSLParameters params) {
        try {
            List<SNIServerName> serverNames = params.getServerNames();
            if (serverNames != null && !serverNames.isEmpty()) {
                for (SNIServerName serverName : serverNames) {
                    if (serverName.getType() == 0) { // SNI host name type
                        String sniHostname = new String(serverName.getEncoded());
                        String peerHost = engine.getPeerHost();

                        // Store mapping
                        sniMappings.put(peerHost + ":" + engine.getPeerPort(), sniHostname);

                        // Emit custom JFR event
                        SNIHandshakeEvent event = new SNIHandshakeEvent();
                        event.sniHostname = sniHostname;
                        event.resolvedHost = peerHost;
                        event.peerAddress = peerHost;
                        event.peerPort = engine.getPeerPort();
                        event.connectionType = "CLIENT";
                        event.threadName = Thread.currentThread().getName();

                        if (event.isEnabled()) {
                            event.begin();
                            event.commit();
                            log.info("Captured SNI: {} -> {}", sniHostname, peerHost);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error capturing SNI: {}", e.getMessage());
        }
    }

    /**
     * Called when SSLEngine.beginHandshake is invoked
     */
    public static void onBeginHandshake(SSLEngine engine) {
        try {
            SSLSession session = engine.getSession();
            String peerHost = engine.getPeerHost();
            int peerPort = engine.getPeerPort();
            String key = peerHost + ":" + peerPort;

            // Check if we have SNI for this connection
            String sniHostname = sniMappings.get(key);
            if (sniHostname != null) {
                // Emit event with actual SNI
                SNIHandshakeEvent event = new SNIHandshakeEvent();
                event.sniHostname = sniHostname;
                event.resolvedHost = peerHost;
                event.peerAddress = peerHost;
                event.peerPort = peerPort;
                event.protocolVersion = session.getProtocol();
                event.cipherSuite = session.getCipherSuite();
                event.connectionType = engine.getUseClientMode() ? "CLIENT" : "SERVER";
                event.threadName = Thread.currentThread().getName();

                if (event.isEnabled()) {
                    event.begin();
                    event.commit();
                    log.info("Handshake with SNI: {}", sniHostname);
                }
            }
        } catch (Exception e) {
            log.error("Error in handshake: {}", e.getMessage());
        }
    }
}