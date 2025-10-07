package com.kafka.jfr.sni;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ClientCertificateCNExtractorTest {

    @Test
    void testConvert_WithValidClientCert() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("CN=client.example.com,OU=Test,O=TestOrg,C=US");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertEquals("client.example.com", result);
    }

    @Test
    void testConvert_WithNullSession() {
        // Act
        String result = ClientCertificateCNExtractor.convert((SSLSession) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNullEngine() {
        // Act
        String result = ClientCertificateCNExtractor.convert((SSLEngine) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNoPeerCertificates() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        when(mockSession.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("No peer cert"));

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithEmptyCertArray() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[0]);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNonX509Certificate() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        Certificate mockCert = Mockito.mock(Certificate.class); // Not X509Certificate

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithCNOnly() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("CN=simple-client");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertEquals("simple-client", result);
    }

    @Test
    void testConvert_WithComplexDN() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal(
            "CN=kafka-client.prod.example.com,OU=Platform,OU=Engineering,O=Example Inc,L=San Francisco,ST=CA,C=US"
        );

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertEquals("kafka-client.prod.example.com", result);
    }

    @Test
    void testConvert_WithLowercaseCN() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("cn=lowercase-client,ou=Test,o=TestOrg");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertEquals("lowercase-client", result);
    }

    @Test
    void testConvert_WithNoCN() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("OU=Test,O=TestOrg,C=US");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithException() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        when(mockSession.getPeerCertificates()).thenThrow(new RuntimeException("Unexpected error"));

        // Act - should not throw, should return null
        String result = ClientCertificateCNExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithValidEngine() throws Exception {
        // Arrange
        SSLEngine mockEngine = Mockito.mock(SSLEngine.class);
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("CN=client.example.com,OU=Test,O=TestOrg");

        when(mockEngine.getSession()).thenReturn(mockSession);
        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = ClientCertificateCNExtractor.convert(mockEngine);

        // Assert
        assertEquals("client.example.com", result);
    }
}
