# 🚀 Production Deployment Guide: SNI JFR Capture Agent

## Overview
This guide covers deploying the SNI JFR capture agent in production Kafka environments to monitor and record actual SNI (Server Name Indication) values sent by clients.

## 📦 Artifacts

### Core Agent JAR
- **File**: `sni-jfr-agent-1.0.0.jar`
- **Main Class**: `com.kafka.jfr.sni.ProductionSNIAgent`
- **Size**: ~500KB (includes Javassist dependency)

### Dependencies
- **JDK**: Java 17+ (for JFR support)
- **Javassist**: 3.29.2-GA (bundled in shaded JAR)

## 🔧 Installation

### 1. Build Production Agent
```bash
cd sni-jfr-events
mvn clean package
```
**Output**: `target/sni-jfr-agent-1.0.0.jar`

### 2. Deploy Agent JAR
```bash
# Copy to your Kafka deployment directory
cp target/sni-jfr-agent-1.0.0.jar /opt/kafka/lib/
```

## ⚙️ Configuration

### Environment Variables
```bash
# Required: Add agent to JVM options
export KAFKA_OPTS="$KAFKA_OPTS -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"

# Optional: Configure output location
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr"

# Optional: Enable debug logging
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=true"

# Optional: Set max recording size (default: 100MB)
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.maxSize=200000000"
```

### Kafka Server Configuration
Add to `kafka-server-start.sh` or systemd service:
```bash
#!/bin/bash
export KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar -Dsni.jfr.output=/var/log/kafka/sni-capture.jfr"
exec /opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
```

### Kafka Client Configuration
For client-side SNI capture:
```bash
# Add to client application startup
export KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"

# Or add to specific command
KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar" kafka-topics --bootstrap-server kafka.example.com:9093 --list
```

## 🚀 Deployment Scenarios

### Scenario 1: Kafka Broker Monitoring
**Use Case**: Monitor SNI values sent to Kafka brokers

```bash
# Add to broker startup
export KAFKA_OPTS="$KAFKA_OPTS -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.output=/var/log/kafka/broker-sni.jfr"

# Start broker
/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
```

### Scenario 2: Client Application Monitoring
**Use Case**: Monitor SNI sent by Kafka clients

```bash
# Add to client application JVM
java -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar \
     -Dsni.jfr.output=./client-sni.jfr \
     -jar your-kafka-client.jar
```

### Scenario 3: Load Balancer/Proxy Analysis
**Use Case**: Analyze SNI patterns through load balancers

```bash
# For applications behind load balancers
export KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.output=/shared/logs/proxy-sni.jfr"
```

## 📊 Monitoring & Analysis

### Real-time Monitoring
```bash
# Monitor agent status in logs
tail -f /var/log/kafka/server.log | grep "PROD-SNI"

# Expected output:
[PROD-SNI] Starting production SNI capture agent...
[PROD-SNI] ✅ SNI CAPTURED: kafka-client.example.com (count: 1)
[PROD-SNI] Status - Total captures: 5, Active mappings: 3
```

### JFR Analysis
```bash
# List custom events
jfr print --events kafka.sni.Handshake /var/log/kafka/sni-capture.jfr

# Filter by connection type
jfr print --events kafka.sni.Handshake /var/log/kafka/sni-capture.jfr | grep "PRODUCTION_SNI_CAPTURE"

# Export to JSON for analysis
jfr print --json /var/log/kafka/sni-capture.jfr > sni-analysis.json
```

### Key Metrics to Monitor
```bash
# Count of SNI captures
jfr print --events kafka.sni.Handshake sni-capture.jfr | grep -c "sniHostname"

# Unique SNI hostnames
jfr print --events kafka.sni.Handshake sni-capture.jfr | grep "sniHostname" | sort | uniq

# Time-based analysis
jfr print --events kafka.sni.Handshake sni-capture.jfr | grep "startTime"
```

## 🔒 Security Considerations

### Data Sensitivity
- **SNI Data**: Contains hostname information (not sensitive but consider privacy)
- **JFR Files**: May contain timing information useful for performance analysis
- **Log Output**: Agent logs actual SNI hostnames

### File Permissions
```bash
# Secure JFR output files
chmod 640 /var/log/kafka/sni-capture.jfr
chown kafka:kafka /var/log/kafka/sni-capture.jfr

# Rotate logs regularly
# Add to logrotate.d/kafka
/var/log/kafka/*.jfr {
    daily
    missingok
    rotate 7
    compress
    notifempty
    create 640 kafka kafka
}
```

## 🎯 Performance Impact

### Expected Overhead
- **CPU**: < 0.1% additional CPU usage
- **Memory**: ~5MB heap for agent
- **I/O**: Minimal (JFR buffers events)
- **Network**: None (no network calls)

### Performance Tuning
```bash
# Reduce JFR buffer size for high-throughput
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.maxSize=50000000"

# Disable debug logging in production
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=false"

# Adjust JFR flush intervals
export KAFKA_OPTS="$KAFKA_OPTS -XX:FlushInterval=30"
```

## 🚨 Troubleshooting

### Common Issues

#### 1. Agent Not Loading
```bash
# Check KAFKA_OPTS
echo $KAFKA_OPTS | grep javaagent

# Verify JAR exists
ls -la /opt/kafka/lib/sni-jfr-agent-1.0.0.jar

# Check logs for agent startup
grep "PROD-SNI.*Starting" /var/log/kafka/server.log
```

#### 2. No SNI Captures
```bash
# Verify SSL connections are being made
netstat -an | grep :9093

# Check if setServerNames is being called
grep "PROD-SNI.*SNI CAPTURED" /var/log/kafka/server.log

# Enable debug mode temporarily
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=true"
```

#### 3. JFR File Issues
```bash
# Check file permissions
ls -la /var/log/kafka/sni-capture.jfr

# Verify JFR file validity
jfr summary /var/log/kafka/sni-capture.jfr

# Check disk space
df -h /var/log/kafka/
```

## 📈 Production Checklist

### Pre-Deployment
- [ ] Build and test agent JAR
- [ ] Verify Java 17+ on target systems
- [ ] Plan JFR output file locations
- [ ] Set up log rotation
- [ ] Configure monitoring alerts

### Deployment
- [ ] Deploy agent JAR to all Kafka nodes
- [ ] Update startup scripts with agent configuration
- [ ] Restart Kafka services (rolling restart recommended)
- [ ] Verify agent loading in logs
- [ ] Test SNI capture with sample connections

### Post-Deployment
- [ ] Monitor performance impact
- [ ] Verify JFR files are being created
- [ ] Set up automated analysis scripts
- [ ] Configure alerting for capture failures
- [ ] Document operational procedures

## 🔄 Maintenance

### Regular Tasks
```bash
# Weekly: Check agent status
grep "PROD-SNI.*Status" /var/log/kafka/server.log | tail -10

# Daily: Verify JFR file growth
ls -lah /var/log/kafka/sni-capture.jfr

# Monthly: Analyze SNI patterns
jfr print --events kafka.sni.Handshake /var/log/kafka/sni-capture.jfr | \
  grep "sniHostname" | awk '{print $3}' | sort | uniq -c | sort -nr
```

### Upgrades
1. Test new agent version in staging
2. Deploy new JAR to all nodes
3. Rolling restart with health checks
4. Verify compatibility with JFR analysis tools

This deployment guide ensures reliable production operation of the SNI JFR capture agent.