# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This project provides a **custom converter for JMC Agent** that captures SSL/TLS metadata (SNI hostnames and client certificate CN) from Kafka connections using Java Flight Recorder (JFR). Instead of building a custom ASM-based Java agent, it leverages JMC Agent's declarative XML configuration system to instrument Kafka's `SslTransportLayer` class.

**Key Insight**: The solution is **server-side only** - deployed on Kafka brokers to capture SNI values sent by clients with minimal (<1%) performance overhead compared to 30-50% overhead from `-Djavax.net.debug=ssl`.

## Current Architecture (JMC Agent + Converter)

The project consists of only **1 Java file**:

1. **SSLSessionSNIConverter.java** - Custom converter that extracts SNI hostname and client certificate CN from `SSLEngine` objects

The instrumentation is defined declaratively in:
- **kafka-ssl-jfr.xml** - JMC Agent configuration file

The converter runs on the server (broker) and extracts SNI values from incoming client connections.

## How SNI Capture Works

### Client Side (No Agent)
```java
// Normal Kafka client configuration
props.put("bootstrap.servers", "broker1.kafka.example.com:9093");
props.put("security.protocol", "SSL");
// Client sends SNI="broker1.kafka.example.com" in TLS ClientHello
```

### Server Side (With JMC Agent + Converter)
```
Client ClientHello with SNI
        ↓
Broker's SslTransportLayer.handshakeFinished() called
        ↓
[JMC Agent intercepts method entry]
        ↓
Captures this.sslEngine field
        ↓
SSLSessionSNIConverter.convert(sslEngine) called
        ↓
ExtendedSSLSession.getRequestedServerNames()
        ↓
Extract SNI hostname from SNIServerName list
        ↓
JFR Event: sniHostname="broker1.kafka.example.com"
```

## Build Commands

```bash
# Build the converter JAR
mvn clean package

# Output location: target/kafka-ssl-jfr-1.0.0.jar

# Build without tests
mvn clean package -DskipTests

# Run tests (unit + integration)
mvn test

# Run E2E test only
mvn test -Dtest=KafkaJMCAgentE2ETest
```

## Deployment

### On Kafka Broker
```bash
export CLASSPATH="/path/to/kafka-ssl-jfr-1.0.0.jar:$CLASSPATH"
export KAFKA_OPTS="-javaagent:/path/to/jmc-agent.jar=/path/to/kafka-ssl-jfr.xml"
bin/kafka-server-start.sh config/server.properties
```

**Important:** The converter JAR must be on the classpath (via `CLASSPATH` env var) so JMC Agent can find the `SSLSessionSNIConverter` class.

### JFR Analysis Commands

```bash
# View SSL handshake events captured by the broker
jfr print --events kafka.ssl.Handshake kafka-ssl.jfr

# Export to JSON
jfr print --json --events kafka.ssl.Handshake kafka-ssl.jfr > events.json

# Check active JFR recording in running broker
jcmd <broker-pid> JFR.check

# Dump JFR recording from running broker
jcmd <broker-pid> JFR.dump name=agent-main filename=kafka-ssl.jfr
```

## Architecture Details

### Entry Point
- **JMC Agent** loads and parses `kafka-ssl-jfr.xml` configuration
- No `premain()` method in this project (JMC Agent provides the agent infrastructure)
- Converter JAR must be on classpath before JMC Agent starts

### Core Components and Data Flow

1. **JMC Agent Initialization** (external to this project)
   - Parses `kafka-ssl-jfr.xml` configuration
   - Finds `SSLSessionSNIConverter` class on classpath
   - Instruments `org.apache.kafka.common.network.SslTransportLayer`
   - Creates JFR event type `kafka.ssl.Handshake`

2. **Declarative Bytecode Instrumentation** (kafka-ssl-jfr.xml)
   - Targets `SslTransportLayer.handshakeFinished()` method
   - Captures at `ENTRY` location (after handshake completes)
   - Reads field: `this.sslEngine`
   - Reads field: `this.channelId`
   - Calls converter for sslEngine field

3. **Server-Side SNI Capture Flow**:
   ```
   Client SSL/TLS Connection → Broker's SSLEngine
                                        ↓
                              Processes ClientHello with SNI
                                        ↓
                    SslTransportLayer.handshakeFinished() called
                                        ↓
                         [JMC Agent-Instrumented Code Executes]
                                        ↓
                      Captures this.sslEngine and this.channelId
                                        ↓
                      SSLSessionSNIConverter.convert(sslEngine)
                                        ↓
                      engine.getSession() → ExtendedSSLSession
                                        ↓
                      extSession.getRequestedServerNames()
                                        ↓
                      Extract SNI hostname from SNIServerName list
                                        ↓
                      Create JFR event with sniHostname + channelId
                                        ↓
                      event.commit() → JFR Recording
   ```

4. **JFR Event** (generated dynamically by JMC Agent)
   - Event name: `kafka.ssl.Handshake`
   - Fields: `sniHostname` (String), `clientCertCN` (String), `channelId` (String)
   - Stack trace enabled (configured in XML)
   - `clientCertCN` is null when client authentication is not enabled

## Configuration

JMC Agent configuration in `kafka-ssl-jfr.xml`:

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

### JMC Agent Expression Constraints

**CRITICAL:** JMC Agent expressions can only reference fields, not call methods:

- ✅ Valid: `this.sslEngine` (field access)
- ❌ Invalid: `this.sslEngine.getSession()` (method call)

Method calls must be moved into the converter implementation.

## Dependencies

- **No runtime dependencies** - Converter uses only JDK classes
- **Java 11+**: Required for `ExtendedSSLSession` and JFR APIs
- **Test dependencies**:
  - JUnit 5
  - AssertJ
  - Mockito
  - Testcontainers (for E2E tests)
  - Kafka clients (for E2E tests)

## Key Implementation Details

### Converter Design

The converter provides two main converter methods with overloads:

```java
// Called by JMC Agent (expression captures SSLEngine)
public static String convert(SSLEngine engine) {
    if (engine == null) return null;
    return convert(engine.getSession());  // Delegate to session method
}

// Called by SSLEngine overload
public static String convert(SSLSession session) {
    if (session == null) return null;
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

// Extract client certificate CN (for client authentication)
public static String convertClientCN(SSLEngine engine) {
    if (engine == null) return null;
    return convertClientCN(engine.getSession());
}

public static String convertClientCN(SSLSession session) {
    if (session == null) return null;

    try {
        Certificate[] peerCertificates = session.getPeerCertificates();
        if (peerCertificates == null || peerCertificates.length == 0) return null;

        X509Certificate cert = (X509Certificate) peerCertificates[0];
        String dn = cert.getSubjectX500Principal().getName();
        return extractCN(dn);  // Extract CN from DN string

    } catch (SSLPeerUnverifiedException e) {
        return null;  // Client auth not enabled
    }
}
```

### Thread Safety
- Converter is stateless and thread-safe
- JFR events are thread-safe by design
- Handles concurrent client connections safely

### Error Handling
All converter logic is wrapped in try-catch that returns `null` on error. This ensures broker operations are never disrupted by converter failures.

### Performance Optimizations
- Converter performs simple type checking and field access
- No reflection or complex operations
- Returns early on null/invalid inputs
- JMC Agent only calls converter when event is enabled

## Testing

The project includes three test suites:

### 1. Unit Tests (SSLSessionSNIConverterTest.java)
- Tests converter with mocked SSLSession objects
- Validates null/empty handling
- Tests international domain names
- Tests multiple SNI values

### 2. Integration Tests (SSLSessionSNIConverterIntegrationTest.java)
- Tests converter with real SSL/TLS handshakes
- Uses BouncyCastle to generate self-signed certificates
- Validates converter works with actual SSLEngine sessions
- Tests both with and without SNI

### 3. E2E Tests (KafkaJMCAgentE2ETest.java)
- Full integration test with Kafka broker in Docker
- Uses Testcontainers for isolated environment
- Verifies JMC Agent + converter + Kafka integration
- Validates JFR events are captured correctly
- See [E2E-TEST-README.md](E2E-TEST-README.md) for details

Run tests with:
```bash
# All tests
mvn test

# Unit + integration only
mvn test -Dtest=SSLSessionSNIConverter*Test

# E2E only
mvn test -Dtest=KafkaJMCAgentE2ETest
```

## Common Development Tasks

### Modifying Converter Logic
1. Update `SSLSessionSNIConverter.convert()` methods
2. Add/update unit tests in `SSLSessionSNIConverterTest`
3. Verify integration tests still pass
4. Optionally run E2E test to validate end-to-end

### Modifying JFR Event Fields
1. Update `kafka-ssl-jfr.xml` `<fields>` section
2. Add new field with `<expression>` and optional `<converter>`
3. Remember: expressions must be field references only (no method calls)
4. Update E2E test assertions to verify new fields

### Targeting Different Application Classes
1. Update `<class>` and `<method>` in `kafka-ssl-jfr.xml`
2. Ensure target class is in application classloader (not bootstrap)
3. Verify method descriptor matches exactly
4. Update documentation to reflect new target

### Debugging JMC Agent Issues
1. Enable JMC Agent debug logging:
   ```bash
   -javaagent:jmc-agent.jar=kafka-ssl-jfr.xml,loglevel=debug
   ```
2. Check converter is found on classpath:
   ```bash
   jcmd <pid> VM.system_properties | grep java.class.path
   ```
3. Verify JFR recording status:
   ```bash
   jcmd <pid> JFR.check
   ```
4. Check for JMC Agent initialization errors in broker logs

## Important Notes

1. **Server-Only Deployment**: JMC Agent + converter deployed only on servers (Kafka brokers), not on clients
2. **Classpath Requirement**: Converter JAR must be on classpath via `CLASSPATH` env var (not `-cp` flag which replaces classpath)
3. **SNI Source**: Captured SNI values come from client's `bootstrap.servers` configuration
4. **No Client Modification**: Clients connect normally without any agent or code changes
5. **Kafka-Specific**: This solution targets Kafka's `SslTransportLayer`. For other applications, update XML to target their SSL handler classes.

## JMC Agent Limitations

- **Cannot instrument bootstrap classes** (e.g., `sun.security.ssl.SSLSessionImpl`)
- **Expressions cannot call methods** - Only field access allowed
- **SystemClassLoader only** - Target classes must be application-loaded
- **No array access in expressions** - Must use converter for complex operations

## Resources

- [JMC Agent Documentation](https://github.com/openjdk/jmc/blob/master/agent/README.md)
- [JMC Agent Converters](https://github.com/openjdk/jmc/tree/master/agent/src/main/java/org/openjdk/jmc/agent/converters)
- [JDK Flight Recorder Guide](https://docs.oracle.com/en/java/javase/17/jfapi/)
- [SNI RFC 6066](https://datatracker.ietf.org/doc/html/rfc6066#section-3)
- [Kafka SSL Configuration](https://kafka.apache.org/documentation/#security_ssl)
