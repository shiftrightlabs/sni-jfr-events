package com.kafka.jfr.sni;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that validates SSLSessionSNIConverter with real SSLEngine instances
 * performing actual TLS handshakes with SNI.
 */
class SSLSessionSNIConverterIntegrationTest {

    private static KeyStore serverKeyStore;
    private static KeyStore clientKeyStore;
    private static KeyStore clientTrustStore;
    private static KeyStore serverTrustStore;
    private static final String SNI_HOSTNAME = "broker.kafka.example.com";
    private static final String CLIENT_CN = "kafka-client.example.com";
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    @BeforeAll
    static void setupSSLInfrastructure() throws Exception {
        // Register BouncyCastle provider
        Security.addProvider(new BouncyCastleProvider());

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));

        // Generate server certificate
        KeyPair serverKeyPair = keyGen.generateKeyPair();
        X509Certificate serverCert = generateSelfSignedCertificate(
            serverKeyPair,
            "CN=" + SNI_HOSTNAME
        );

        // Generate client certificate
        KeyPair clientKeyPair = keyGen.generateKeyPair();
        X509Certificate clientCert = generateSelfSignedCertificate(
            clientKeyPair,
            "CN=" + CLIENT_CN + ",OU=Platform,O=Example Inc"
        );

        // Create server keystore with private key and certificate
        serverKeyStore = KeyStore.getInstance("PKCS12");
        serverKeyStore.load(null, null);
        serverKeyStore.setKeyEntry(
            "server",
            serverKeyPair.getPrivate(),
            KEYSTORE_PASSWORD,
            new Certificate[]{serverCert}
        );

        // Create client keystore with private key and certificate
        clientKeyStore = KeyStore.getInstance("PKCS12");
        clientKeyStore.load(null, null);
        clientKeyStore.setKeyEntry(
            "client",
            clientKeyPair.getPrivate(),
            KEYSTORE_PASSWORD,
            new Certificate[]{clientCert}
        );

        // Create client truststore with server certificate
        clientTrustStore = KeyStore.getInstance("PKCS12");
        clientTrustStore.load(null, null);
        clientTrustStore.setCertificateEntry("server-cert", serverCert);

        // Create server truststore with client certificate (for client auth)
        serverTrustStore = KeyStore.getInstance("PKCS12");
        serverTrustStore.load(null, null);
        serverTrustStore.setCertificateEntry("client-cert", clientCert);
    }

    @Test
    void testConverterWithRealSSLEngine_ClientSendsSNI() throws Exception {
        // Arrange: Create SSL contexts
        SSLContext serverContext = createServerSSLContext();
        SSLContext clientContext = createClientSSLContext();

        // Create SSL engines
        SSLEngine serverEngine = serverContext.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(false);

        SSLEngine clientEngine = clientContext.createSSLEngine(SNI_HOSTNAME, 9093);
        clientEngine.setUseClientMode(true);

        // Set SNI on client
        SSLParameters clientParams = clientEngine.getSSLParameters();
        clientParams.setServerNames(java.util.List.of(new SNIHostName(SNI_HOSTNAME)));
        clientEngine.setSSLParameters(clientParams);

        // Act: Perform handshake
        performHandshake(clientEngine, serverEngine);

        // Extract session from server side
        SSLSession serverSession = serverEngine.getSession();

        // Use converter to extract SNI
        String extractedSNI = SSLSessionSNIConverter.convert(serverSession);

        // Assert: Verify SNI was captured correctly
        assertNotNull(extractedSNI, "SNI should be captured from server session");
        assertEquals(SNI_HOSTNAME, extractedSNI, "Extracted SNI should match sent SNI");

        // Verify session is valid
        assertTrue(serverSession.isValid(), "SSL session should be valid");
        assertNotNull(serverSession.getCipherSuite(), "Cipher suite should be negotiated");
        assertFalse(serverSession.getCipherSuite().contains("NULL"), "Cipher should not be NULL");
    }

    @Test
    void testConverterWithRealSSLEngine_NoSNISent() throws Exception {
        // Arrange: Create SSL contexts
        SSLContext serverContext = createServerSSLContext();
        SSLContext clientContext = createClientSSLContext();

        // Create SSL engines without SNI
        SSLEngine serverEngine = serverContext.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(false);

        // Client engine created without hostname - no SNI will be sent
        SSLEngine clientEngine = clientContext.createSSLEngine();
        clientEngine.setUseClientMode(true);

        // Act: Perform handshake
        performHandshake(clientEngine, serverEngine);

        // Extract session from server side
        SSLSession serverSession = serverEngine.getSession();

        // Use converter to extract SNI
        String extractedSNI = SSLSessionSNIConverter.convert(serverSession);

        // Assert: No SNI should be present
        assertNull(extractedSNI, "SNI should be null when client doesn't send SNI");
    }

    @Test
    void testConverterWithRealSSLEngine_InternationalDomainName() throws Exception {
        // Arrange: International domain (IDN) in ACE format
        String idnHostname = "xn--bcher-kva.example.com"; // bücher.example.com

        SSLContext serverContext = createServerSSLContext();
        SSLContext clientContext = createClientSSLContext();

        SSLEngine serverEngine = serverContext.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(false);

        SSLEngine clientEngine = clientContext.createSSLEngine(idnHostname, 9093);
        clientEngine.setUseClientMode(true);

        // Set IDN as SNI
        SSLParameters clientParams = clientEngine.getSSLParameters();
        clientParams.setServerNames(java.util.List.of(new SNIHostName(idnHostname)));
        clientEngine.setSSLParameters(clientParams);

        // Act: Perform handshake
        performHandshake(clientEngine, serverEngine);

        SSLSession serverSession = serverEngine.getSession();
        String extractedSNI = SSLSessionSNIConverter.convert(serverSession);

        // Assert: IDN should be preserved in ACE format
        assertNotNull(extractedSNI);
        assertEquals(idnHostname, extractedSNI, "IDN should be preserved in ACE format");
    }

    @Test
    void testConverterWithRealSSLEngine_ClientCertificateAuth() throws Exception {
        // Arrange: Create SSL contexts with client authentication
        SSLContext serverContext = createServerSSLContextWithClientAuth();
        SSLContext clientContext = createClientSSLContextWithCert();

        // Create SSL engines
        SSLEngine serverEngine = serverContext.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true); // Require client certificate

        SSLEngine clientEngine = clientContext.createSSLEngine(SNI_HOSTNAME, 9093);
        clientEngine.setUseClientMode(true);

        // Set SNI on client
        SSLParameters clientParams = clientEngine.getSSLParameters();
        clientParams.setServerNames(java.util.List.of(new SNIHostName(SNI_HOSTNAME)));
        clientEngine.setSSLParameters(clientParams);

        // Act: Perform handshake
        performHandshake(clientEngine, serverEngine);

        // Extract session from server side
        SSLSession serverSession = serverEngine.getSession();

        // Use converters to extract both SNI and client cert CN
        String extractedSNI = SSLSessionSNIConverter.convert(serverSession);
        String extractedClientCN = SSLSessionSNIConverter.convertClientCN(serverSession);

        // Assert: Both SNI and client CN should be captured
        assertNotNull(extractedSNI, "SNI should be captured from server session");
        assertEquals(SNI_HOSTNAME, extractedSNI, "Extracted SNI should match sent SNI");

        assertNotNull(extractedClientCN, "Client CN should be captured from peer certificate");
        assertEquals(CLIENT_CN, extractedClientCN, "Extracted client CN should match client certificate CN");

        // Verify session has peer certificates
        assertNotNull(serverSession.getPeerCertificates(), "Server should have peer certificates");
        assertTrue(serverSession.getPeerCertificates().length > 0, "At least one peer certificate should exist");
    }

    @Test
    void testConverterWithRealSSLEngine_NoClientAuth() throws Exception {
        // Arrange: Create SSL contexts without client authentication
        SSLContext serverContext = createServerSSLContext();
        SSLContext clientContext = createClientSSLContext();

        SSLEngine serverEngine = serverContext.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(false); // No client authentication

        SSLEngine clientEngine = clientContext.createSSLEngine(SNI_HOSTNAME, 9093);
        clientEngine.setUseClientMode(true);

        SSLParameters clientParams = clientEngine.getSSLParameters();
        clientParams.setServerNames(java.util.List.of(new SNIHostName(SNI_HOSTNAME)));
        clientEngine.setSSLParameters(clientParams);

        // Act: Perform handshake
        performHandshake(clientEngine, serverEngine);

        SSLSession serverSession = serverEngine.getSession();

        // Extract both SNI and client CN
        String extractedSNI = SSLSessionSNIConverter.convert(serverSession);
        String extractedClientCN = SSLSessionSNIConverter.convertClientCN(serverSession);

        // Assert: SNI should be present, but no client CN (no client auth)
        assertNotNull(extractedSNI, "SNI should be captured");
        assertEquals(SNI_HOSTNAME, extractedSNI);

        assertNull(extractedClientCN, "Client CN should be null when client auth is not enabled");
    }

    // ========== Helper Methods ==========

    private SSLContext createServerSSLContext() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(serverKeyStore, KEYSTORE_PASSWORD);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private SSLContext createClientSSLContext() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(clientTrustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), new SecureRandom());
        return context;
    }

    private SSLContext createServerSSLContextWithClientAuth() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(serverKeyStore, KEYSTORE_PASSWORD);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(serverTrustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return context;
    }

    private SSLContext createClientSSLContextWithCert() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(clientKeyStore, KEYSTORE_PASSWORD);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(clientTrustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return context;
    }

    /**
     * Performs a complete TLS handshake between client and server engines.
     * This uses a simplified approach suitable for testing.
     */
    private void performHandshake(SSLEngine clientEngine, SSLEngine serverEngine) throws Exception {
        // Begin handshake
        clientEngine.beginHandshake();
        serverEngine.beginHandshake();

        // Create buffers - note these are network buffers for encrypted data
        ByteBuffer clientToServerNet = ByteBuffer.allocate(clientEngine.getSession().getPacketBufferSize());
        ByteBuffer serverToClientNet = ByteBuffer.allocate(serverEngine.getSession().getPacketBufferSize());

        // Application data buffers (not used during handshake but required by API)
        ByteBuffer clientAppData = ByteBuffer.allocate(clientEngine.getSession().getApplicationBufferSize());
        ByteBuffer serverAppData = ByteBuffer.allocate(serverEngine.getSession().getApplicationBufferSize());

        ByteBuffer emptyBuffer = ByteBuffer.allocate(0);

        int maxIterations = 100;
        int iteration = 0;

        while (iteration++ < maxIterations) {
            SSLEngineResult.HandshakeStatus clientStatus = clientEngine.getHandshakeStatus();
            SSLEngineResult.HandshakeStatus serverStatus = serverEngine.getHandshakeStatus();

            // Check if both are done
            if (clientStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                serverStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                return; // Handshake complete
            }

            // Process client engine
            if (clientStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                clientEngine.wrap(emptyBuffer, clientToServerNet);
            } else if (clientStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                serverToClientNet.flip();
                clientEngine.unwrap(serverToClientNet, clientAppData);
                serverToClientNet.compact();
            } else if (clientStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                runDelegatedTasks(clientEngine);
            }

            // Process server engine
            if (serverStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                serverEngine.wrap(emptyBuffer, serverToClientNet);
            } else if (serverStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                clientToServerNet.flip();
                serverEngine.unwrap(clientToServerNet, serverAppData);
                clientToServerNet.compact();
            } else if (serverStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                runDelegatedTasks(serverEngine);
            }
        }

        throw new IOException("Handshake did not complete within " + maxIterations + " iterations");
    }

    private void runDelegatedTasks(SSLEngine engine) {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }

    /**
     * Generates a self-signed X.509 certificate for testing using BouncyCastle.
     */
    private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair, String dn)
            throws Exception {

        Date notBefore = Date.from(Instant.now());
        Date notAfter = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));

        X500Name issuer = new X500Name(dn);
        BigInteger serialNumber = new BigInteger(64, new SecureRandom());

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serialNumber,
                notBefore,
                notAfter,
                issuer,
                keyPair.getPublic()
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .build(keyPair.getPrivate());

        return new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));
    }
}
