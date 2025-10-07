# Kafka SSL JFR

A custom converter for [JMC Agent](https://github.com/openjdk/jmc) that captures SSL/TLS metadata (SNI hostnames and client certificate CN) from Kafka connections using Java Flight Recorder (JFR).

## Overview

This project provides a simple, declarative way to monitor SSL/TLS connection metadata from clients connecting to Kafka brokers. Instead of building a custom Java agent with ASM bytecode instrumentation, it leverages **JMC Agent's converter system** to extract SSL/TLS information from Kafka's `SslTransportLayer`.

### Key Features

- ✅ **Declarative XML Configuration** - No complex ASM code to maintain
- ✅ **Minimal Dependencies** - Single converter class, no runtime dependencies
- ✅ **Works with Application Classes** - Instruments Kafka's `SslTransportLayer` (not JDK internals)
- ✅ **Low Overhead** - JFR's efficient event recording (<1% performance impact)
- ✅ **Captures SNI and Client Identity** - SNI hostname, client certificate CN, and Kafka channel ID for correlation

## Architecture

```
Client SSL/TLS Connection → Kafka Broker
                                  ↓
                    SslTransportLayer.handshakeFinished()
                                  ↓
                         [JMC Agent Intercepts]
                                  ↓
                  Captures this.sslEngine field
                                  ↓
            SSLSessionSNIConverter.convert(sslEngine)
                                  ↓
        ExtendedSSLSession.getRequestedServerNames()
                                  ↓
                    Extract SNI Hostname
                                  ↓
               JFR Event: kafka.sni.Handshake
```

## Requirements

1. **JMC Agent JAR** - Download from [OpenJDK JMC releases](https://github.com/openjdk/jmc/releases)
2. **This Converter JAR** - Build with `mvn clean package`
3. **Java 11+** - Required for JFR and SSL APIs
4. **Kafka Broker with SSL Enabled** - Target application

## Build

```bash
mvn clean package
```

Output: `target/kafka-ssl-jfr-1.0.0.jar`

## Usage

### 1. Download JMC Agent

```bash
# Download from Adoptium JMC Build releases (recommended)
wget https://github.com/adoptium/jmc-build/releases/download/8.3.0/agent-1.0.1.jar
mv agent-1.0.1.jar jmc-agent.jar
```

### 2. Configure Kafka Broker

Add the following to your Kafka startup script or `KAFKA_OPTS`:

```bash
export CLASSPATH="/path/to/kafka-ssl-jfr-1.0.0.jar:$CLASSPATH"
export KAFKA_OPTS="-javaagent:/path/to/jmc-agent.jar=/path/to/kafka-ssl-jfr.xml"

bin/kafka-server-start.sh config/server.properties
```

**Important**: The converter JAR must be on the classpath (via `CLASSPATH` env var) so JMC Agent can find `SSLSessionSNIConverter`.

### 3. Start Kafka Broker

When the broker starts, JMC Agent will:
1. Load `kafka-ssl-jfr.xml` configuration
2. Find `SSLSessionSNIConverter` on the classpath
3. Instrument `SslTransportLayer.handshakeFinished()`
4. Start recording JFR events to the default output file

### 4. Analyze JFR Events

After clients connect, view the captured SNI events:

```bash
# View all SSL handshake events
jfr print --events kafka.ssl.Handshake /path/to/recording.jfr

# Export to JSON
jfr print --json --events kafka.ssl.Handshake /path/to/recording.jfr > ssl-events.json

# Check active recording
jcmd <broker-pid> JFR.check

# Dump recording from running broker
jcmd <broker-pid> JFR.dump name=agent-main filename=kafka-ssl.jfr
```

### Example Event Output

**Without Client Authentication:**
```
kafka.ssl.Handshake {
  startTime = 15:23:45.123
  sniHostname = "broker1.kafka.example.com"
  clientCertCN = null
  channelId = "192.168.1.100:9093-192.168.1.200:54321-0"
  eventThread = "kafka-network-thread-9093-3"
  stackTrace = [...]
}
```

**With Client Authentication:**
```
kafka.ssl.Handshake {
  startTime = 15:23:45.123
  sniHostname = "broker1.kafka.example.com"
  clientCertCN = "kafka-client.prod.example.com"
  channelId = "192.168.1.100:9093-192.168.1.200:54321-0"
  eventThread = "kafka-network-thread-9093-3"
  stackTrace = [...]
}
```

## Configuration

The `kafka-ssl-jfr.xml` file defines the JMC Agent instrumentation:

```xml
<jfragent>
    <config>
        <allowconverter>true</allowconverter>
    </config>
    <events>
        <event id="kafka.ssl.Handshake">
            <class>org.apache.kafka.common.network.SslTransportLayer</class>
            <method>
                <name>handshakeFinished</name>
                <descriptor>()V</descriptor>
            </method>
            <location>ENTRY</location>
            <fields>
                <field>
                    <name>sniHostname</name>
                    <expression>this.sslEngine</expression>
                    <converter>com.kafka.jfr.sni.SSLSessionSNIConverter</converter>
                </field>
                <field>
                    <name>clientCertCN</name>
                    <expression>this.sslEngine</expression>
                    <converter>com.kafka.jfr.sni.SSLSessionSNIConverter.convertClientCN(Ljavax/net/ssl/SSLEngine;)Ljava/lang/String;</converter>
                </field>
                <field>
                    <name>channelId</name>
                    <expression>this.channelId</expression>
                </field>
            </fields>
        </event>
    </events>
</jfragent>
```

### Custom Converter

The `SSLSessionSNIConverter` class provides two converter methods:

**1. Extract SNI hostname:**
```java
public static String convert(SSLEngine engine) {
    if (engine == null) return null;
    SSLSession session = engine.getSession();
    if (!(session instanceof ExtendedSSLSession)) return null;

    ExtendedSSLSession extSession = (ExtendedSSLSession) session;
    List<SNIServerName> serverNames = extSession.getRequestedServerNames();
    if (serverNames == null || serverNames.isEmpty()) return null;

    SNIServerName serverName = serverNames.get(0);
    if (serverName instanceof SNIHostName) {
        return ((SNIHostName) serverName).getAsciiName();
    }
    return null;
}
```

**2. Extract client certificate CN (requires client authentication):**
```java
public static String convertClientCN(SSLEngine engine) {
    if (engine == null) return null;
    SSLSession session = engine.getSession();

    try {
        Certificate[] certs = session.getPeerCertificates();
        if (certs == null || certs.length == 0) return null;

        X509Certificate cert = (X509Certificate) certs[0];
        String dn = cert.getSubjectX500Principal().getName();
        // Extract CN from DN (e.g., "CN=client.example.com,OU=...")
        return extractCN(dn);
    } catch (SSLPeerUnverifiedException e) {
        return null; // Client auth not enabled
    }
}
```

## Advantages vs. Custom Agent

| Aspect | JMC Agent + Converter | Custom ASM Agent |
|--------|----------------------|------------------|
| Configuration | Declarative XML | Programmatic ASM |
| Maintenance | Simple | Complex |
| Dependencies | None (runtime) | ASM libraries |
| Target Classes | Application (Kafka) | JDK internals |
| Classloader Issues | None | Bootstrap classpath required |
| Module System | No issues | Requires `redefineModule()` |

## Limitations

### Kafka-Specific

This solution only works with **Apache Kafka** because it instruments `org.apache.kafka.common.network.SslTransportLayer`. For other applications:

- **Netty**: Instrument `io.netty.handler.ssl.SslHandler`
- **Jetty**: Instrument `org.eclipse.jetty.io.ssl.SslConnection`
- **Tomcat**: Instrument `org.apache.tomcat.util.net.SecureNioChannel`
- **Generic**: Use a custom Java agent to instrument JDK's `SSLSessionImpl` (requires bootstrap classloader)

### JMC Agent Limitations

- **Cannot instrument bootstrap classes** (e.g., `sun.security.ssl.SSLSessionImpl`)
- **Converter expressions are limited** - No method calls, array access, or object creation
- **SystemClassLoader only** - Classes must be loaded by the application classloader

## Performance

- **JMC Agent overhead**: ~1-2% (bytecode injection at startup)
- **JFR overhead**: <1% (efficient binary format, lock-free)
- **Converter overhead**: Negligible (simple field access + cast)

Compare to `-Djavax.net.debug=ssl`: **30-50% overhead**

## Client Authentication Support

When Kafka is configured with `ssl.client.auth=required` or `ssl.client.auth=requested`, the converter will capture the **Common Name (CN)** from the client certificate in addition to the SNI hostname.

### Enabling Client Authentication on Kafka

Add to `server.properties`:
```properties
ssl.client.auth=required
ssl.truststore.location=/path/to/truststore.jks
ssl.truststore.password=changeit
```

### Certificate Requirements

- **Server certificate**: Must include SANs for all hostnames clients will connect to
- **Client certificate**: Must be signed by a CA trusted by the server (in truststore)
- **Same CA**: Both server and client certificates should be signed by the same CA for mutual trust

### Example Certificate Setup

```bash
# Generate CA
keytool -genkeypair -alias ca -keystore ca.jks -storepass changeit \
    -keyalg RSA -keysize 2048 -validity 365 \
    -dname "CN=Test CA,OU=Test,O=Test,C=US" -ext bc=ca:true

# Generate server cert signed by CA
keytool -genkeypair -alias server -keystore server.keystore.jks \
    -storepass changeit -keyalg RSA -keysize 2048 \
    -dname "CN=kafka-broker.example.com,OU=Broker,O=Example,C=US" \
    -ext san=dns:kafka-broker.example.com,dns:broker-alias.example.com

# Generate client cert signed by CA
keytool -genkeypair -alias client -keystore client.keystore.jks \
    -storepass changeit -keyalg RSA -keysize 2048 \
    -dname "CN=kafka-client.prod.example.com,OU=Client,O=Example,C=US"
```

### Benefits

- **Enhanced Security Audit**: Track which client certificates are connecting to which SNI hostnames
- **Correlation**: Associate client identity with connection metadata and SNI
- **Compliance**: Meet regulatory requirements for tracking client access

## Testing

### Automated Tests

Run all tests:

```bash
mvn test
```

**Test Coverage:**

1. **Unit Tests** (`SSLSessionSNIConverterTest.java`) - 19 tests
   - SNI extraction from ExtendedSSLSession
   - Client certificate CN extraction from peer certificates
   - Null/empty session handling
   - Exception safety (converter never throws)
   - International domain names (IDN/ACE format)
   - Multiple SNI values (takes first)
   - Complex Distinguished Name parsing
   - No client authentication scenarios

2. **Integration Tests** (`SSLSessionSNIConverterIntegrationTest.java`) - 5 tests
   - Real SSLEngine TLS handshake with SNI
   - Real SSLEngine TLS handshake without SNI
   - Real SSLEngine with client certificate authentication (mTLS)
   - Real SSLEngine without client authentication
   - International domain name SNI in real handshake
   - Uses keytool for certificate generation
   - Validates converter works with actual SSL/TLS sessions

3. **End-to-End Tests** (`KafkaJMCAgentE2ETest.java`) - 1 test
   - Full Kafka broker with SSL and client authentication (mTLS)
   - JMC Agent instrumentation with converter on classpath
   - Real Kafka producer connecting with client certificate
   - Two-hostname approach (broker CN vs. client alias) for SNI testing
   - Verifies both SNI hostname and client certificate CN captured in JFR events
   - Uses Testcontainers with Docker
   - Automatically downloads JMC Agent JAR via Maven

**Total:** 25 tests, all passing ✓

### End-to-End Testing

**Automated E2E Test (Testcontainers):**
- [E2E-TEST-README.md](E2E-TEST-README.md) - Comprehensive E2E testing with Docker
- Requires: JMC Agent JAR + Docker
- Runs Kafka in container with full SSL + JMC Agent integration
- Run with: `mvn test -Dtest=KafkaJMCAgentE2ETest`

**Manual Kafka Testing:**
- [MANUAL-KAFKA-TEST.md](MANUAL-KAFKA-TEST.md) - Step-by-step manual validation guide
- For production-like environment testing

## Troubleshooting

### Converter not found

**Error**: `ClassNotFoundException: com.kafka.jfr.sni.SSLSessionSNIConverter`

**Solution**: Ensure converter JAR is on classpath via `CLASSPATH` environment variable before starting Kafka

### No events captured

1. Check JFR recording is active: `jcmd <pid> JFR.check`
2. Verify SSL connections are actually made
3. Enable JMC Agent debug logging:
   ```bash
   -javaagent:jmc-agent.jar=kafka-ssl-jfr.xml,loglevel=debug
   ```

### Wrong method instrumented

Check that `handshakeFinished()` exists in your Kafka version:
```bash
javap -cp kafka-libs.jar org.apache.kafka.common.network.SslTransportLayer | grep handshakeFinished
```

### SNI hostname is null

**Causes:**
1. Client didn't send SNI (used IP address instead of hostname)
2. Client connected to hostname that exactly matches certificate CN (no SNI needed)

**Solutions:**
1. Ensure client connects to hostname: `bootstrap.servers=broker.example.com:9093`
2. Use two-hostname approach (see MANUAL-KAFKA-TEST.md) to guarantee SNI is sent

### Client certificate CN is null

**Cause**: Client authentication not enabled on broker

**Solution**: Set `ssl.client.auth=required` in broker configuration

## References

- [JMC Agent Documentation](https://github.com/openjdk/jmc/blob/master/agent/README.md)
- [JMC Agent Converters](https://github.com/openjdk/jmc/tree/master/agent/src/main/java/org/openjdk/jmc/agent/converters)
- [JDK Flight Recorder Guide](https://docs.oracle.com/en/java/javase/17/jfapi/)
- [SNI RFC 6066](https://datatracker.ietf.org/doc/html/rfc6066#section-3)

## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

This project uses code patterns and concepts from OpenJDK JMC Agent, which is licensed under the Universal Permissive License v1.0 (UPL).
