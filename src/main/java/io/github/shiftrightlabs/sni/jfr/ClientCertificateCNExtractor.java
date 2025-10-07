package io.github.shiftrightlabs.sni.jfr;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * JMC Agent converter that extracts the Common Name (CN) from client certificates.
 *
 * This converter is specifically designed to extract the CN field from the subject
 * Distinguished Name (DN) of client X.509 certificates during mutual TLS authentication.
 * It follows the single responsibility principle by focusing exclusively on client
 * certificate CN extraction.
 *
 * Usage in JMC Agent XML:
 * <pre>
 * &lt;field&gt;
 *   &lt;name&gt;clientCertCN&lt;/name&gt;
 *   &lt;expression&gt;this.sslEngine&lt;/expression&gt;
 *   &lt;converter&gt;io.github.shiftrightlabs.sni.jfr.ClientCertificateCNExtractor&lt;/converter&gt;
 * &lt;/field&gt;
 * </pre>
 */
public final class ClientCertificateCNExtractor {

    // Private constructor to prevent instantiation
    private ClientCertificateCNExtractor() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Converts an SSLEngine to the client certificate CN (Common Name).
     * JMC Agent calls this when the expression returns SSLEngine.
     *
     * @param engine the SSLEngine from which to extract client certificate CN
     * @return the client certificate CN, or null if not available
     */
    public static String convert(SSLEngine engine) {
        if (engine == null) {
            return null;
        }
        return convert(engine.getSession());
    }

    /**
     * Converts an SSLSession to the client certificate CN (Common Name).
     * Extracts the CN from the peer certificate's subject DN when client
     * authentication is enabled.
     *
     * @param session the SSLSession from which to extract client certificate CN
     * @return the client certificate CN, or null if not available
     */
    public static String convert(SSLSession session) {
        if (session == null) {
            return null;
        }

        try {
            // Get peer certificates (client certificates when client auth is enabled)
            Certificate[] peerCertificates = session.getPeerCertificates();

            if (peerCertificates == null || peerCertificates.length == 0) {
                return null;
            }

            // Get the first certificate (client's certificate)
            Certificate cert = peerCertificates[0];
            if (!(cert instanceof X509Certificate)) {
                return null;
            }

            X509Certificate x509Cert = (X509Certificate) cert;
            X500Principal subjectPrincipal = x509Cert.getSubjectX500Principal();

            if (subjectPrincipal == null) {
                return null;
            }

            // Parse the DN to extract CN
            String dn = subjectPrincipal.getName();
            return extractCN(dn);

        } catch (SSLPeerUnverifiedException e) {
            // Client authentication not enabled or client didn't present cert
            return null;
        } catch (Exception e) {
            // Never throw exceptions from converters
            return null;
        }
    }

    /**
     * Extracts the CN (Common Name) from a distinguished name string.
     * Handles both "CN=value" and "CN=value,..." formats.
     *
     * @param dn the distinguished name (e.g., "CN=client.example.com,OU=Test,O=Test")
     * @return the CN value, or null if CN not found
     */
    private static String extractCN(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }

        // Look for CN= pattern (case-insensitive)
        String[] parts = dn.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.toUpperCase().startsWith("CN=")) {
                return trimmed.substring(3).trim();
            }
        }

        return null;
    }
}
