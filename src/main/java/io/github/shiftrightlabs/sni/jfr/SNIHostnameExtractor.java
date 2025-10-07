package io.github.shiftrightlabs.sni.jfr;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.util.List;

/**
 * JMC Agent converter that extracts SNI (Server Name Indication) hostname from SSL/TLS sessions.
 *
 * This converter is specifically designed to extract the SNI hostname sent by clients
 * during the TLS handshake. It follows the single responsibility principle by focusing
 * exclusively on SNI hostname extraction.
 *
 * Usage in JMC Agent XML:
 * <pre>
 * &lt;field&gt;
 *   &lt;name&gt;sniHostname&lt;/name&gt;
 *   &lt;expression&gt;this.sslEngine&lt;/expression&gt;
 *   &lt;converter&gt;io.github.shiftrightlabs.sni.jfr.SNIHostnameExtractor&lt;/converter&gt;
 * &lt;/field&gt;
 * </pre>
 */
public final class SNIHostnameExtractor {

    // Private constructor to prevent instantiation
    private SNIHostnameExtractor() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Converts an SSLEngine to the SNI hostname string.
     * JMC Agent calls this when the expression returns SSLEngine.
     *
     * @param engine the SSLEngine from which to extract SNI
     * @return the SNI hostname, or null if not available
     */
    public static String convert(SSLEngine engine) {
        if (engine == null) {
            return null;
        }
        return convert(engine.getSession());
    }

    /**
     * Converts an SSLSession to the SNI hostname string.
     *
     * @param session the SSLSession from which to extract SNI
     * @return the SNI hostname, or null if not available
     */
    public static String convert(SSLSession session) {
        if (session == null) {
            return null;
        }

        try {
            // Cast to ExtendedSSLSession to access SNI information
            if (!(session instanceof ExtendedSSLSession)) {
                return null;
            }

            ExtendedSSLSession extendedSession = (ExtendedSSLSession) session;
            List<SNIServerName> serverNames = extendedSession.getRequestedServerNames();

            if (serverNames == null || serverNames.isEmpty()) {
                return null;
            }

            // Extract the first SNI hostname (typically there's only one)
            SNIServerName serverName = serverNames.get(0);
            if (serverName instanceof SNIHostName) {
                return ((SNIHostName) serverName).getAsciiName();
            }

            return null;
        } catch (Exception e) {
            // Never throw exceptions from converters - they're called at event sites
            // Return null if anything goes wrong
            return null;
        }
    }
}
