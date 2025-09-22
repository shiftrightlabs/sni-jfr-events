# ⚙️ Configuration Templates & Examples

## Important: Where to Deploy the Agent

⚠️ **The SNI JFR agent should ONLY be deployed on Kafka BROKERS, not on clients!**

- ✅ **Deploy on**: Kafka brokers (servers that accept SSL connections)
- ❌ **NOT on**: Kafka clients, producers, consumers, or client applications

**Why?**
- SNI (Server Name Indication) is sent BY clients TO servers during SSL handshake
- The server (broker) receives and can capture the SNI value
- Clients don't need the agent - they automatically send SNI based on the hostname they connect to
- Installing the agent on clients would be unnecessary overhead with no benefit

## Environment Configuration Templates

### 1. Production Kafka Broker
```bash
#!/bin/bash
# /opt/kafka/config/broker-with-sni-agent.env

# Core agent configuration
export KAFKA_OPTS="$KAFKA_OPTS -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"

# SNI JFR Configuration
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.output=/var/log/kafka/broker-sni-$(hostname)-$(date +%Y%m%d).jfr"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=false"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.maxSize=200000000"  # 200MB

# JFR Performance tuning
export KAFKA_OPTS="$KAFKA_OPTS -XX:FlushInterval=60"
export KAFKA_OPTS="$KAFKA_OPTS -XX:StartFlightRecording=maxsize=100m,maxage=24h"

# Memory settings for agent
export KAFKA_HEAP_OPTS="-Xmx4g -Xms4g"
```

### 2. Development Environment
```bash
#!/bin/bash
# /opt/kafka/config/dev-with-sni-agent.env

# Agent with debug enabled
export KAFKA_OPTS="$KAFKA_OPTS -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.output=./dev-sni-capture.jfr"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=true"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.maxSize=50000000"  # 50MB for dev

# Additional JFR events for debugging
export KAFKA_OPTS="$KAFKA_OPTS -XX:StartFlightRecording=duration=60s,filename=dev-startup.jfr"
```

### 3. Client Application (No Agent Needed)
```bash
#!/bin/bash
# kafka-client-app.sh

APP_NAME="kafka-client-app"

# NOTE: SNI capture agent is NOT needed on clients!
# The SNI values sent by clients are captured on the Kafka broker side.
# Clients just need proper SSL configuration to send SNI.

# Standard Kafka client configuration
export JAVA_OPTS="-Xmx1g -Xms1g"

# SSL configuration for client (SNI is sent automatically based on bootstrap server hostname)
export JAVA_OPTS="$JAVA_OPTS -Dssl.endpoint.identification.algorithm=https"

# Start application - no agent needed
java $JAVA_OPTS -jar ${APP_NAME}.jar
```

## Systemd Service Templates

### 1. Kafka Broker with SNI Agent
```ini
# /etc/systemd/system/kafka-with-sni.service
[Unit]
Description=Apache Kafka with SNI JFR Agent
Documentation=https://kafka.apache.org/
Requires=network.target remote-fs.target
After=network.target remote-fs.target

[Service]
Type=simple
User=kafka
Group=kafka
Environment="KAFKA_OPTS=-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr"
Environment="KAFKA_HEAP_OPTS=-Xmx4g -Xms4g"
Environment="KAFKA_JVM_PERFORMANCE_OPTS=-server -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:InitiatingHeapOccupancyPercent=35 -XX:+ExplicitGCInvokesConcurrent -Djava.awt.headless=true"
ExecStart=/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
ExecStop=/opt/kafka/bin/kafka-server-stop.sh
Restart=on-abnormal
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### 2. Kafka Client Service (No Agent Needed)
```ini
# /etc/systemd/system/kafka-client.service
[Unit]
Description=Kafka Client Application
After=network.target

[Service]
Type=simple
User=kafka-client
Group=kafka-client
WorkingDirectory=/opt/kafka-client
# NOTE: No SNI agent needed on client side!
# SNI is captured on the broker where this client connects
Environment="JAVA_OPTS=-Xmx1g -Xms1g"
ExecStart=/usr/bin/java $JAVA_OPTS -jar kafka-client-app.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## Docker Configuration

### 1. Kafka Broker Dockerfile
```dockerfile
# Dockerfile.kafka-sni
FROM confluentinc/cp-kafka:7.6.6

# Copy SNI agent
COPY sni-jfr-agent-1.0.0.jar /opt/kafka/lib/

# Create log directory
RUN mkdir -p /var/log/kafka && chown appuser:appuser /var/log/kafka

# Set environment for SNI capture
ENV KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr"

# Expose JFR files as volume
VOLUME ["/var/log/kafka"]
```

### 2. Docker Compose Configuration
```yaml
# docker-compose.yml
version: '3.8'
services:
  kafka-with-sni:
    build:
      context: .
      dockerfile: Dockerfile.kafka-sni
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: SSL://kafka:9093
      KAFKA_SSL_KEYSTORE_LOCATION: /etc/kafka/secrets/keystore.jks
      KAFKA_SSL_TRUSTSTORE_LOCATION: /etc/kafka/secrets/truststore.jks
      # SNI Agent Configuration
      KAFKA_OPTS: >-
        -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar
        -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr
        -Dsni.jfr.debug=false
        -Dsni.jfr.maxSize=100000000
    volumes:
      - ./logs:/var/log/kafka
      - ./secrets:/etc/kafka/secrets
    ports:
      - "9093:9093"
```

## Kubernetes Deployment

### 1. ConfigMap for Agent Configuration
```yaml
# kafka-sni-config.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: kafka-sni-config
data:
  sni-agent.env: |
    KAFKA_OPTS=-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr -Dsni.jfr.debug=false
```

### 2. Kafka StatefulSet with SNI Agent
```yaml
# kafka-with-sni.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka-with-sni
spec:
  serviceName: kafka
  replicas: 3
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      initContainers:
      - name: install-sni-agent
        image: busybox
        command: ['sh', '-c', 'cp /agent/sni-jfr-agent-1.0.0.jar /opt/kafka/lib/']
        volumeMounts:
        - name: sni-agent
          mountPath: /agent
        - name: kafka-libs
          mountPath: /opt/kafka/lib
      containers:
      - name: kafka
        image: confluentinc/cp-kafka:7.6.6
        envFrom:
        - configMapRef:
            name: kafka-sni-config
        volumeMounts:
        - name: kafka-logs
          mountPath: /var/log/kafka
        - name: kafka-libs
          mountPath: /opt/kafka/lib
      volumes:
      - name: sni-agent
        configMap:
          name: sni-agent-jar
      - name: kafka-libs
        emptyDir: {}
  volumeClaimTemplates:
  - metadata:
      name: kafka-logs
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
```

## Application Properties

### 1. Spring Boot Configuration
```properties
# application.properties
# Standard Kafka client configuration - NO agent needed
spring.kafka.bootstrap-servers=kafka-cluster.example.com:9093
spring.kafka.security.protocol=SSL
spring.kafka.ssl.trust-store-location=classpath:truststore.jks
spring.kafka.ssl.key-store-location=classpath:keystore.jks

# SNI is automatically sent based on the bootstrap server hostname
# The broker (with agent) will capture the SNI value
# No agent configuration needed on client side
```

### 2. Java Client Application
```bash
# Standard client startup - NO agent needed
# SNI capture happens on the Kafka broker side

java -Xmx1g -Xms1g \
     -Dssl.endpoint.identification.algorithm=https \
     -jar your-kafka-client-app.jar

# NOTE: The SNI agent should be installed on the Kafka BROKER, not the client
# Clients automatically send SNI based on the hostname they connect to
```

## Monitoring Configuration

### 1. Prometheus Metrics (Custom Extension)
```java
// Custom metrics exporter for SNI data
@Component
public class SNIMetricsExporter {

    @EventListener
    public void handleSNICapture(SNIHandshakeEvent event) {
        Counter.builder("kafka.sni.captures")
            .tag("hostname", event.sniHostname)
            .tag("thread", event.threadName)
            .register(Metrics.globalRegistry)
            .increment();
    }
}
```

### 2. Grafana Dashboard Query Templates
```promql
# SNI capture rate
rate(kafka_sni_captures_total[5m])

# Unique SNI hostnames
count by (hostname) (kafka_sni_captures_total)

# SNI captures by thread
sum by (thread) (kafka_sni_captures_total)
```

## Capturing Client Certificate Details

### Using Standard JFR Events
While this project focuses on SNI capture, JFR also provides standard events that capture client certificate details during mTLS (mutual TLS) authentication:

```bash
# Enable additional JFR events to capture certificate details
export KAFKA_OPTS="$KAFKA_OPTS -XX:StartFlightRecording=settings=profile,+jdk.X509Certificate#enabled=true,+jdk.X509Validation#enabled=true,+jdk.TLSHandshake#enabled=true"
```

These standard JFR events capture:
- **jdk.X509Certificate**: Client certificate details including CN (Common Name), Subject, Issuer
- **jdk.X509Validation**: Certificate validation results
- **jdk.TLSHandshake**: Handshake details (but without SNI)

### Combined Configuration for Complete TLS Visibility
```bash
# Complete TLS monitoring: SNI + Client Certificates
export KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar \
    -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr \
    -XX:StartFlightRecording=filename=/var/log/kafka/tls-complete.jfr,settings=profile,\
+jdk.X509Certificate#enabled=true,\
+jdk.X509Validation#enabled=true,\
+jdk.TLSHandshake#enabled=true,\
+kafka.sni.Handshake#enabled=true"
```

### Analyzing Client Certificate CN with SNI
```bash
#!/bin/bash
# analyze-sni-and-certs.sh

JFR_FILE="${1:-/var/log/kafka/tls-complete.jfr}"

echo "=== Combined SNI and Certificate Analysis ==="

# Extract SNI hostnames
echo "SNI Hostnames:"
jfr print --events kafka.sni.Handshake "$JFR_FILE" | \
    grep "sniHostname" | awk '{print $3}' | sort -u

echo ""
echo "Client Certificate CNs:"
# Extract certificate Common Names
jfr print --events jdk.X509Certificate "$JFR_FILE" | \
    grep -E "subject.*CN=" | \
    sed 's/.*CN=\([^,]*\).*/\1/' | sort -u

echo ""
echo "Correlation:"
echo "SNI hostname -> Certificate CN mapping helps identify:"
echo "- Clients using aliases vs actual certificate names"
echo "- Certificate mismatches with intended hostnames"
echo "- Multi-domain certificate usage patterns"
```

### Example: Correlating SNI with Client Certificate
```java
// JFR Event Correlation Example
// This shows how SNI and Certificate events can be correlated

// Custom event (from this project)
kafka.sni.Handshake {
  startTime = 10:45:23.456
  sniHostname = "kafka-client-alias.example.com"  // Client's intended hostname
  threadName = "kafka-network-thread-1"
}

// Standard JFR event (provided by JVM)
jdk.X509Certificate {
  startTime = 10:45:23.457
  subject = "CN=kafka-client.example.com, O=Example Corp"  // Actual certificate CN
  issuer = "CN=Example CA"
  validFrom = "2024-01-01"
  validUntil = "2025-01-01"
}

// The combination shows:
// - Client connects using alias: kafka-client-alias.example.com (SNI)
// - But certificate CN is: kafka-client.example.com
// - This is valid if the certificate has the alias as a SAN entry
```

## Analysis Scripts

### 1. SNI Analysis Script
```bash
#!/bin/bash
# analyze-sni.sh

JFR_FILE="${1:-/var/log/kafka/sni-capture.jfr}"

echo "=== SNI Analysis Report ==="
echo "File: $JFR_FILE"
echo "Generated: $(date)"
echo

# Total captures
total=$(jfr print --events kafka.sni.Handshake "$JFR_FILE" | grep -c "sniHostname")
echo "Total SNI captures: $total"
echo

# Unique hostnames
echo "Unique SNI hostnames:"
jfr print --events kafka.sni.Handshake "$JFR_FILE" | \
    grep "sniHostname" | \
    awk '{print $3}' | \
    sort | uniq -c | sort -nr
echo

# Time range
echo "Time range:"
jfr print --events kafka.sni.Handshake "$JFR_FILE" | \
    grep "startTime" | \
    head -1 | awk '{print "Start:", $3}'
jfr print --events kafka.sni.Handshake "$JFR_FILE" | \
    grep "startTime" | \
    tail -1 | awk '{print "End:  ", $3}'
```

### 2. Real-time Monitoring
```bash
#!/bin/bash
# monitor-sni.sh

LOG_FILE="/var/log/kafka/server.log"

echo "Monitoring SNI captures in real-time..."
echo "Press Ctrl+C to stop"

tail -f "$LOG_FILE" | grep --line-buffered "PROD-SNI.*SNI CAPTURED" | while read line; do
    timestamp=$(echo "$line" | awk '{print $1, $2}')
    hostname=$(echo "$line" | grep -o 'SNI CAPTURED: [^(]*' | sed 's/SNI CAPTURED: //')
    count=$(echo "$line" | grep -o 'count: [0-9]*' | sed 's/count: //')

    printf "[%s] %-30s (Total: %s)\n" "$timestamp" "$hostname" "$count"
done
```

These configuration templates provide comprehensive coverage for deploying the SNI JFR agent across different environments and platforms.