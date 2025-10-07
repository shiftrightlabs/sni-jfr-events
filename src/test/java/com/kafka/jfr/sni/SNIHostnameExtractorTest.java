package com.kafka.jfr.sni;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class SNIHostnameExtractorTest {

    @Test
    void testConvert_WithValidSNI() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        SNIHostName sniHostName = new SNIHostName("broker.kafka.example.com");
        List<SNIServerName> serverNames = Collections.singletonList(sniHostName);

        when(mockSession.getRequestedServerNames()).thenReturn(serverNames);

        // Act
        String result = SNIHostnameExtractor.convert(mockSession);

        // Assert
        assertEquals("broker.kafka.example.com", result);
    }

    @Test
    void testConvert_WithNullSession() {
        // Act
        String result = SNIHostnameExtractor.convert((SSLSession) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNonExtendedSSLSession() {
        // Arrange
        SSLSession mockSession = Mockito.mock(SSLSession.class);

        // Act
        String result = SNIHostnameExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithEmptyServerNames() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        when(mockSession.getRequestedServerNames()).thenReturn(Collections.emptyList());

        // Act
        String result = SNIHostnameExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithNullServerNames() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        when(mockSession.getRequestedServerNames()).thenReturn(null);

        // Act
        String result = SNIHostnameExtractor.convert(mockSession);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithException() {
        // Arrange
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        when(mockSession.getRequestedServerNames()).thenThrow(new RuntimeException("Test exception"));

        // Act - should not throw, should return null
        String result = SNIHostnameExtractor.convert(mockSession);

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
        String result = SNIHostnameExtractor.convert(mockSession);

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
        String result = SNIHostnameExtractor.convert(mockSession);

        // Assert
        assertEquals("broker1.kafka.example.com", result);
    }

    @Test
    void testConvert_WithNullEngine() {
        // Act
        String result = SNIHostnameExtractor.convert((SSLEngine) null);

        // Assert
        assertNull(result);
    }

    @Test
    void testConvert_WithValidEngine() {
        // Arrange
        SSLEngine mockEngine = Mockito.mock(SSLEngine.class);
        ExtendedSSLSession mockSession = Mockito.mock(ExtendedSSLSession.class);
        SNIHostName sniHostName = new SNIHostName("broker.kafka.example.com");
        List<SNIServerName> serverNames = Collections.singletonList(sniHostName);

        when(mockEngine.getSession()).thenReturn(mockSession);
        when(mockSession.getRequestedServerNames()).thenReturn(serverNames);

        // Act
        String result = SNIHostnameExtractor.convert(mockEngine);

        // Assert
        assertEquals("broker.kafka.example.com", result);
    }
}
