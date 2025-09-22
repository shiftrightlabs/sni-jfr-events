package com.kafka.jfr.sni;

import jdk.jfr.*;
import java.time.Duration;

@Name("kafka.sni.Handshake")
@Label("SNI Handshake")
@Category({"Kafka", "TLS", "SNI"})
@Description("Captures SNI hostname from TLS handshake")
@StackTrace(false)
@Threshold("0ms")
public class SNIHandshakeEvent extends Event {

    @Label("SNI Hostname")
    @Description("The SNI hostname sent by client in ClientHello")
    public String sniHostname;

    @Label("Resolved Host")
    @Description("The resolved hostname after DNS lookup")
    public String resolvedHost;

    @Label("Peer Address")
    @Description("The peer IP address")
    public String peerAddress;

    @Label("Peer Port")
    public int peerPort;

    @Label("Protocol Version")
    public String protocolVersion;

    @Label("Cipher Suite")
    public String cipherSuite;

    @Label("Connection Type")
    @Description("CLIENT or SERVER side of connection")
    public String connectionType;

    @Label("Thread Name")
    public String threadName;

    @Timespan(Timespan.MILLISECONDS)
    @Label("Handshake Duration")
    public long handshakeDuration;
}