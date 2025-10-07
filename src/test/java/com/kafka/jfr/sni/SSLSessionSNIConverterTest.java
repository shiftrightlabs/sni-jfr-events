package com.kafka.jfr.sni;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.auth.x500.X500Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class SSLSessionSNIConverterTest {

    @Test
    void testConvert_WithValidSNI() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        SNIHostName sniHostName = new SNIHostName("broker.kafka.example.com");
        List<SNIServerName> serverNames = Collections.singletonList(sniHostName);

        when(mockSession.getRequestedServerNames()).thenReturn(serverNames);

        // Act
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertEquals("broker.kafka.example.com", result);
    }

    @Test
    void testConvert_WithNullSession() {
        // Act
        String result = SSLSessionSNIConverter.convert((SSLSession) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNonExtendedSSLSession() {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);

        // Act
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithEmptyServerNames() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        when(mockSession.getRequestedServerNames()).thenReturn(Collections.emptyList());

        // Act
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNullServerNames() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        when(mockSession.getRequestedServerNames()).thenReturn(null);

        // Act
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithException() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        when(mockSession.getRequestedServerNames()).thenThrow(new RuntimeException("Test exception"));

        // Act - should not throw, should return null
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithInternationalDomainName() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        // Use ASCII-compatible encoding (ACE) for international domain
        SNIHostName sniHostName = new SNIHostName("xn--bcher-kva.example.com"); // bücher.example.com
        List<SNIServerName> serverNames = Collections.singletonList(sniHostName);

        when(mockSession.getRequestedServerNames()).thenReturn(serverNames);

        // Act
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertEquals("xn--bcher-kva.example.com", result);
    }

    @Test
    void testConvert_WithMultipleSNI_TakesFirst() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        SNIHostName sniHostName1 = new SNIHostName("broker1.kafka.example.com");
        SNIHostName sniHostName2 = new SNIHostName("broker2.kafka.example.com");
        List<SNIServerName> serverNames = List.of(sniHostName1, sniHostName2);

        when(mockSession.getRequestedServerNames()).thenReturn(serverNames);

        // Act
        String result = SSLSessionSNIConverter.convert(mockSession);

        // Assert
        assertEquals("broker1.kafka.example.com", result);
    }

    // ========== Client Certificate CN Tests ==========

    @Test
    void testConvertClientCN_WithValidClientCert() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("CN=client.example.com,OU=Test,O=TestOrg,C=US");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertEquals("client.example.com", result);
    }

    @Test
    void testConvertClientCN_WithNullSession() {
        // Act
        String result = SSLSessionSNIConverter.convertClientCN((SSLSession) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertClientCN_WithNullEngine() {
        // Act
        String result = SSLSessionSNIConverter.convertClientCN((SSLEngine) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertClientCN_WithNoPeerCertificates() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        when(mockSession.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("No peer cert"));

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertClientCN_WithEmptyCertArray() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[0]);

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertClientCN_WithNonX509Certificate() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        Certificate mockCert = Mockito.mock(Certificate.class); // Not X509Certificate

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertClientCN_WithCNOnly() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("CN=simple-client");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertEquals("simple-client", result);
    }

    @Test
    void testConvertClientCN_WithComplexDN() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal(
            "CN=kafka-client.prod.example.com,OU=Platform,OU=Engineering,O=Example Inc,L=San Francisco,ST=CA,C=US"
        );

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertEquals("kafka-client.prod.example.com", result);
    }

    @Test
    void testConvertClientCN_WithLowercaseCN() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("cn=lowercase-client,ou=Test,o=TestOrg");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertEquals("lowercase-client", result);
    }

    @Test
    void testConvertClientCN_WithNoCN() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        X509Certificate mockCert = Mockito.mock(X509Certificate.class);
        X500Principal principal = new X500Principal("OU=Test,O=TestOrg,C=US");

        when(mockSession.getPeerCertificates()).thenReturn(new Certificate[]{mockCert});
        when(mockCert.getSubjectX500Principal()).thenReturn(principal);

        // Act
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertClientCN_WithException() throws Exception {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);
        when(mockSession.getPeerCertificates()).thenThrow(new RuntimeException("Unexpected error"));

        // Act - should not throw, should return null
        String result = SSLSessionSNIConverter.convertClientCN(mockSession);

        // Assert
        assertNull(result);
    }
}
