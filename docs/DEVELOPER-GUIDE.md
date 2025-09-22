# Developer Guide: SNI JFR Events Project

## Table of Contents
1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [How It Works](#how-it-works)
4. [Core Components](#core-components)
5. [Build and Development](#build-and-development)
6. [Testing](#testing)
7. [Debugging](#debugging)
8. [Contributing](#contributing)

## Project Overview

The SNI JFR Events project is a Java agent that captures Server Name Indication (SNI) values during SSL/TLS handshakes in Apache Kafka brokers and records them using Java Flight Recorder (JFR) custom events.

### Problem Statement
Standard JFR TLS events only show the resolved hostname (IP address or DNS-resolved name) but not the actual SNI value sent by clients. This makes it difficult to track which client alias or hostname was actually used in the SSL handshake.

### Solution
A Java agent that uses bytecode instrumentation to intercept SSL/TLS handshake operations and emit custom JFR events containing the actual SNI hostname values.

## Architecture

```
┌─────────────────────┐
│   Kafka Client      │
│  (kafka-client.com) │
└──────────┬──────────┘
           │ SSL/TLS Connection
           │ SNI: kafka-client.com
           ▼
┌─────────────────────┐
│   Kafka Broker      │
│                     │
│  ┌───────────────┐  │
│  │  Java Agent   │  │ ◄── Attached via -javaagent
│  │               │  │
│  │ Instruments:  │  │
│  │ - SSLParams   │  │
│  │ - SSLEngine   │  │
│  └───────┬───────┘  │
│          │          │
│          ▼          │
│  ┌───────────────┐  │
│  │  Custom JFR   │  │
│  │    Events     │  │
│  └───────────────┘  │
└─────────────────────┘
           │
           ▼
    ┌──────────────┐
    │  JFR File    │
    │ (.jfr)       │
    └──────────────┘
```

## How It Works

### 1. Agent Initialization
When the JVM starts with `-javaagent:sni-jfr-agent.jar`, the agent's `premain` method is called before the main application:

```java
public static void premain(String agentArgs, Instrumentation inst) {
    // 1. Register custom JFR event
    FlightRecorder.register(SNIHandshakeEvent.class);

    // 2. Setup JFR recording
    setupJFRRecording();

    // 3. Add bytecode instrumentation
    addInstrumentation(inst);
}
```

### 2. Bytecode Instrumentation
The agent uses Javassist to modify SSL classes at runtime:

```java
// Intercept SSLParameters.setServerNames()
if (className.equals("javax/net/ssl/SSLParameters")) {
    // Insert code to capture SNI when it's set
    instrumentSSLParameters(className, classfileBuffer);
}
```

### 3. SNI Capture Flow

```
Client sets SNI → SSLParameters.setServerNames(List<SNIServerName>)
                            │
                            ▼
                  [Instrumented Code Executes]
                            │
                            ▼
                  Extract SNI hostname from list
                            │
                            ▼
                  Call ProductionSNIAgent.captureSNI()
                            │
                            ▼
                  Create and emit SNIHandshakeEvent
                            │
                            ▼
                  Event recorded in JFR file
```

### 4. Custom JFR Event Structure

```java
@Name("kafka.sni.Handshake")
@Label("SNI Handshake")
@Description("SSL/TLS handshake with SNI information")
@Category({"Kafka", "Network", "Security"})
public class SNIHandshakeEvent extends Event {
    @Label("SNI Hostname")
    public String sniHostname;      // The actual SNI value

    @Label("Resolved Host")
    public String resolvedHost;      // The resolved IP/hostname

    @Label("Connection Type")
    public String connectionType;    // CLIENT/SERVER

    // Additional fields...
}
```

## Core Components

### 1. ProductionSNIAgent.java
Main production agent with:
- SLF4J logging integration
- Configurable JFR recording
- Safe error handling
- Reflection-based instrumentation to avoid classpath issues

### 2. SNIHandshakeEvent.java
Custom JFR event definition that captures:
- SNI hostname (actual client-provided name)
- Resolved hostname (DNS/IP)
- Connection details (port, protocol, cipher)
- Performance metrics (handshake duration)

### 3. SNIProgrammaticAgent.java
Development version with:
- Programmatic JFR configuration
- Debug logging
- Test event emission
- Monitoring thread

### 4. Instrumentation Details

The agent instruments `javax.net.ssl.SSLParameters.setServerNames()`:

```java
// Original method
public void setServerNames(List<SNIServerName> serverNames) {
    this.serverNames = serverNames;
}

// After instrumentation (conceptually)
public void setServerNames(List<SNIServerName> serverNames) {
    // Injected code
    for (SNIServerName sn : serverNames) {
        if (sn.getType() == 0) { // SNI hostname type
            String hostname = new String(sn.getEncoded());
            ProductionSNIAgent.captureSNI(hostname);
        }
    }
    // Original code
    this.serverNames = serverNames;
}
```

## Build and Development

### Prerequisites
- Java 17+
- Maven 3.6+
- Kafka 2.8+ (for testing)

### Building the Agent
```bash
# Clone the repository
cd sni-jfr-events

# Build the agent JAR
mvn clean package

# Output: target/sni-jfr-agent-1.0.0.jar
```

### Project Structure
```
sni-jfr-events/
├── src/main/java/com/kafka/jfr/sni/
│   ├── ProductionSNIAgent.java      # Production agent
│   ├── SNIHandshakeEvent.java       # JFR event definition
│   ├── SNIProgrammaticAgent.java    # Dev/test agent
│   └── ...                          # Supporting classes
├── docs/                            # Documentation
├── scripts/                         # Test scripts
├── examples/                        # Configuration examples
└── pom.xml                         # Maven configuration
```

### Maven Dependencies
```xml
<dependency>
    <groupId>org.javassist</groupId>
    <artifactId>javassist</artifactId>
    <version>3.29.2-GA</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.7.36</version>
    <scope>provided</scope> <!-- Kafka provides this -->
</dependency>
```

## Testing

### Unit Testing (Development)
```bash
# Run with test agent
java -javaagent:target/sni-jfr-agent-1.0.0.jar \
     -Dsni.jfr.debug=true \
     TestClass
```

### Integration Testing with Kafka
```bash
# Start Kafka with the agent
export KAFKA_OPTS="-javaagent:/path/to/sni-jfr-agent.jar"
bin/kafka-server-start.sh config/server.properties

# Connect a client with SNI
kafka-topics.sh --bootstrap-server kafka-alias.example.com:9093 \
                --command-config client-ssl.properties --list
```

### Verifying SNI Capture
```bash
# Parse JFR file
jfr print --events kafka.sni.Handshake kafka-sni-capture.jfr

# Look for events like:
kafka.sni.Handshake {
  startTime = 10:45:23.456
  sniHostname = "kafka-client-alias.example.com"
  resolvedHost = "192.168.1.100"
  connectionType = "CLIENT_SNI_CAPTURE"
  ...
}
```

## Debugging

### Enable Debug Logging
```bash
# Set system property
-Dsni.jfr.debug=true

# Or in log4j.properties
log4j.logger.com.kafka.jfr.sni=DEBUG
```

### Common Issues and Solutions

#### 1. NoClassDefFoundError for SLF4J
**Cause**: SLF4J not in classpath
**Solution**: Ensure running with Kafka broker which provides SLF4J

#### 2. No SNI Events Captured
**Cause**: Instrumentation not applied
**Debug Steps**:
```bash
# Check agent loaded
grep "Starting production SNI" kafka.log

# Verify instrumentation
grep "Instrumenting SSLParameters" kafka.log

# Check JFR recording status
jcmd <pid> JFR.check
```

#### 3. JFR File Not Created
**Cause**: JFR not enabled or misconfigured
**Solution**:
```bash
# Enable JFR explicitly
-XX:StartFlightRecording=filename=test.jfr
```

### Instrumentation Verification
To verify the agent is working:

1. **Check Agent Loading**:
```java
// In your test code
Instrumentation inst = ManagementFactory.getPlatformMXBean(Instrumentation.class);
System.out.println("Agent loaded: " + (inst != null));
```

2. **Monitor SNI Capture Count**:
```java
// The agent tracks capture count
long count = ProductionSNIAgent.getCaptureCount();
System.out.println("SNI captures: " + count);
```

3. **Test Event Emission**:
```java
// Programmatically emit test event
SNIHandshakeEvent event = new SNIHandshakeEvent();
event.sniHostname = "test.example.com";
event.begin();
event.commit();
```

## Contributing

### Code Style
- Use SLF4J for all logging (no System.out)
- Handle all exceptions gracefully
- Use try-with-resources where applicable
- Document complex instrumentation logic

### Adding New Instrumentation Points

1. **Identify Target Method**:
```java
// Find methods that handle SNI
SSLEngine.setSSLParameters()
SSLSocket.setSSLParameters()
```

2. **Create Transformer**:
```java
public byte[] transform(...) {
    if (className.equals("target/class")) {
        return instrumentMethod(classfileBuffer);
    }
}
```

3. **Inject Capture Code**:
```java
CtMethod method = ctClass.getDeclaredMethod("targetMethod");
method.insertBefore("captureSNI($1);");
```

### Testing Checklist
- [ ] Agent loads without errors
- [ ] SNI events are captured
- [ ] JFR file is created
- [ ] No performance degradation
- [ ] Works with mTLS
- [ ] Handles null/empty SNI

### Performance Considerations
- Events are created only when enabled
- No stack traces for high-frequency events
- Async logging for production
- Minimal overhead in instrumented code

## Advanced Topics

### Custom JFC Configuration
Create custom JFR settings:
```xml
<configuration>
  <event name="kafka.sni.Handshake">
    <setting name="enabled">true</setting>
    <setting name="threshold">0 ms</setting>
    <setting name="stackTrace">false</setting>
  </event>
</configuration>
```

### Programmatic JFR Control
```java
// Start recording programmatically
Recording recording = new Recording();
recording.enable("kafka.sni.Handshake");
recording.start();

// ... application runs ...

recording.stop();
recording.dump(Paths.get("output.jfr"));
```

### Integration with Monitoring Systems
Export JFR data to monitoring systems:
```bash
# Convert to JSON
jfr print --json --events kafka.sni.Handshake recording.jfr > events.json

# Parse and send to metrics system
cat events.json | jq '.events[] | select(.type == "kafka.sni.Handshake")' \
                | send-to-prometheus
```

## Conclusion

The SNI JFR Events project provides crucial visibility into SSL/TLS connections in Kafka environments by capturing actual SNI values that standard JFR events miss. The bytecode instrumentation approach ensures compatibility without modifying Kafka source code, while custom JFR events provide seamless integration with existing monitoring tools.

For questions or issues, consult the troubleshooting guide or file an issue in the project repository.