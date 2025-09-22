package com.kafka.jfr.sni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class SNIEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(SNIEventEmitter.class);
    private static final ConcurrentHashMap<Object, Long> handshakeStartTimes = new ConcurrentHashMap<>();

    public static void recordHandshakeStart(SSLEngine engine) {
        try {
            handshakeStartTimes.put(engine, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Error recording handshake start: {}", e.getMessage());
        }
    }

    public static void recordSocketHandshake(SSLSocket socket) {
        try {
            handshakeStartTimes.put(socket, System.currentTimeMillis());

            SNIHandshakeEvent event = new SNIHandshakeEvent();
            event.begin();

            // Get SNI from SSLParameters
            SSLParameters params = socket.getSSLParameters();
            List<SNIServerName> serverNames = params.getServerNames();

            if (serverNames != null && !serverNames.isEmpty()) {
                event.sniHostname = serverNames.get(0).toString();
            } else {
                event.sniHostname = "none";
            }

            // Get connection info
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (remote != null) {
                event.resolvedHost = remote.getHostName();
                event.peerAddress = remote.getAddress().getHostAddress();
                event.peerPort = remote.getPort();
            }

            event.connectionType = "CLIENT";
            event.threadName = Thread.currentThread().getName();
            event.protocolVersion = socket.getSession().getProtocol();
            event.cipherSuite = socket.getSession().getCipherSuite();

            Long startTime = handshakeStartTimes.remove(socket);
            if (startTime != null) {
                event.handshakeDuration = System.currentTimeMillis() - startTime;
            }

            event.commit();

            log.debug("Recorded SSLSocket handshake - SNI: {}", event.sniHostname);

        } catch (Exception e) {
            log.error("Error recording socket handshake: {}", e.getMessage());
        }
    }

    public static void checkClientHello(SSLEngine engine, ByteBuffer output) {
        try {
            // Check if this is a ClientHello message
            if (output != null && output.position() > 5) {
                output.flip();
                byte contentType = output.get();
                output.get(); // major version
                output.get(); // minor version
                output.getShort(); // length

                if (contentType == 22) { // Handshake
                    byte handshakeType = output.get();
                    if (handshakeType == 1) { // ClientHello
                        recordClientHello(engine, output);
                    }
                }
                output.rewind();
            }
        } catch (Exception e) {
            log.error("Error checking ClientHello: {}", e.getMessage());
        }
    }

    private static void recordClientHello(SSLEngine engine, ByteBuffer clientHello) {
        try {
            SNIHandshakeEvent event = new SNIHandshakeEvent();
            event.begin();

            // Try to extract SNI from SSLEngine's parameters
            SSLParameters params = engine.getSSLParameters();
            List<SNIServerName> serverNames = params.getServerNames();

            if (serverNames != null && !serverNames.isEmpty()) {
                event.sniHostname = extractHostname(serverNames.get(0));
            } else {
                // Try to get from the engine's peer host
                event.sniHostname = engine.getPeerHost();
                if (event.sniHostname == null) {
                    event.sniHostname = "none";
                }
            }

            // Get connection info
            event.resolvedHost = engine.getPeerHost();
            event.peerPort = engine.getPeerPort();
            event.connectionType = engine.getUseClientMode() ? "CLIENT" : "SERVER";
            event.threadName = Thread.currentThread().getName();

            // Try to get protocol and cipher from handshake session
            SSLSession session = engine.getHandshakeSession();
            if (session != null) {
                event.protocolVersion = session.getProtocol();
                event.cipherSuite = session.getCipherSuite();
            }

            Long startTime = handshakeStartTimes.remove(engine);
            if (startTime != null) {
                event.handshakeDuration = System.currentTimeMillis() - startTime;
            }

            event.commit();

            log.debug("Recorded SSLEngine ClientHello - SNI: {}, Thread: {}", event.sniHostname, event.threadName);

        } catch (Exception e) {
            log.error("Error recording ClientHello: {}", e.getMessage());
            log.debug("Stack trace:", e);
        }
    }

    private static String extractHostname(SNIServerName serverName) {
        String str = serverName.toString();
        // SNIHostName toString format: "type=host_name (0), value=hostname"
        int valueIndex = str.indexOf("value=");
        if (valueIndex != -1) {
            return str.substring(valueIndex + 6).trim();
        }
        return str;
    }
}