package com.kafka.jfr.sni;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * JMC Agent converter for extracting client certificate Common Name (CN) from SSLEngine.
 * This is a separate converter class because JMC Agent doesn't support method references
 * in the converter attribute (e.g., ClassName#methodName syntax is not valid).
 */
public class SSLEngineClientCNConverter {

    /**
     * Extracts the client certificate CN from an SSLEngine.
     * Called by JMC Agent when instrumenting Kafka's SslTransportLayer.
     *
     * @param engine the SSLEngine to extract client CN from
     * @return the client certificate CN, or null if not available
     */
    public static String convert(SSLEngine engine) {
        return SSLSessionSNIConverter.convertClientCN(engine);
    }

    /**
     * Extracts the client certificate CN from an SSLSession.
     * Overload that accepts SSLSession directly.
     *
     * @param session the SSLSession to extract client CN from
     * @return the client certificate CN, or null if not available
     */
    public static String convert(SSLSession session) {
        return SSLSessionSNIConverter.convertClientCN(session);
    }
}
