# 🔍 Monitoring & Troubleshooting Guide

## Real-time Monitoring

### Agent Status Monitoring

#### 1. Check Agent Initialization
```bash
# Verify agent loaded successfully
grep "PROD-SNI.*Starting production SNI capture agent" /var/log/kafka/server.log

# Expected output:
[PROD-SNI] Starting production SNI capture agent...
[PROD-SNI] Configuration - Output: kafka-sni-capture.jfr, Debug: false
[PROD-SNI] Custom JFR event registered
[PROD-SNI] JFR recording started: kafka-sni-capture.jfr
[PROD-SNI] Production agent initialized successfully
```

#### 2. Monitor SNI Captures
```bash
# Real-time SNI capture monitoring
tail -f /var/log/kafka/server.log | grep "PROD-SNI.*SNI CAPTURED"

# Expected output:
[PROD-SNI] ✅ SNI CAPTURED: kafka-client.example.com (count: 1)
[PROD-SNI] ✅ SNI CAPTURED: kafka-with-alias.domaina.com (count: 2)
```

#### 3. Periodic Status Reports
```bash
# Check agent status reports (every 30 seconds when debug enabled)
grep "PROD-SNI.*Status" /var/log/kafka/server.log | tail -5

# Expected output:
[PROD-SNI] Status - Total captures: 15, Active mappings: 8
```

### JFR File Monitoring

#### 1. File Growth Monitoring
```bash
# Monitor JFR file size growth
watch -n 10 'ls -lah /var/log/kafka/kafka-sni-capture.jfr'

# Alert if file not growing (no SNI activity)
SIZE_CHECK=$(stat -f%z /var/log/kafka/kafka-sni-capture.jfr 2>/dev/null || echo 0)
if [ $SIZE_CHECK -lt 1024 ]; then
    echo "WARNING: JFR file too small or missing"
fi
```

#### 2. JFR File Validation
```bash
# Validate JFR file integrity
jfr summary /var/log/kafka/kafka-sni-capture.jfr

# Check for custom events
jfr print --events kafka.sni.Handshake /var/log/kafka/kafka-sni-capture.jfr | head -10
```

## Automated Monitoring Scripts

### 1. Health Check Script
```bash
#!/bin/bash
# sni-agent-health-check.sh

set -e

JFR_FILE="/var/log/kafka/kafka-sni-capture.jfr"
LOG_FILE="/var/log/kafka/server.log"
ALERT_EMAIL="ops@company.com"

# Function to send alerts
send_alert() {
    local message="$1"
    echo "[$(date)] ALERT: $message" | tee -a /var/log/kafka/sni-alerts.log
    # Uncomment for email alerts
    # echo "$message" | mail -s "SNI Agent Alert" $ALERT_EMAIL
}

# Check 1: Agent initialization
if ! grep -q "PROD-SNI.*Production agent initialized successfully" "$LOG_FILE"; then
    send_alert "SNI Agent failed to initialize"
    exit 1
fi

# Check 2: Recent SNI captures (last 10 minutes)
if ! grep -q "PROD-SNI.*SNI CAPTURED" <(tail -n 1000 "$LOG_FILE"); then
    send_alert "No SNI captures detected in recent activity"
fi

# Check 3: JFR file exists and growing
if [ ! -f "$JFR_FILE" ]; then
    send_alert "JFR file missing: $JFR_FILE"
    exit 1
fi

# Check 4: JFR file size reasonable
FILE_SIZE=$(stat -f%z "$JFR_FILE" 2>/dev/null || echo 0)
if [ $FILE_SIZE -lt 1024 ]; then
    send_alert "JFR file too small: $FILE_SIZE bytes"
fi

# Check 5: JFR file validity
if ! jfr summary "$JFR_FILE" >/dev/null 2>&1; then
    send_alert "JFR file corrupted or invalid"
    exit 1
fi

echo "[$(date)] SNI Agent health check: PASSED"
```

### 2. Performance Monitoring
```bash
#!/bin/bash
# sni-performance-monitor.sh

JFR_FILE="/var/log/kafka/kafka-sni-capture.jfr"
LOG_FILE="/var/log/kafka/server.log"

# Get capture statistics
total_captures=$(grep -c "PROD-SNI.*SNI CAPTURED" "$LOG_FILE" || echo 0)
unique_hostnames=$(grep "PROD-SNI.*SNI CAPTURED" "$LOG_FILE" | awk '{print $6}' | sort | uniq | wc -l)

# Get JFR file size
jfr_size=$(ls -lah "$JFR_FILE" | awk '{print $5}')

# Get memory usage of Kafka process
kafka_pid=$(pgrep -f kafka.Kafka)
if [ -n "$kafka_pid" ]; then
    memory_usage=$(ps -o rss= -p $kafka_pid | awk '{print $1/1024 " MB"}')
else
    memory_usage="N/A"
fi

# Generate report
cat << EOF
SNI Agent Performance Report - $(date)
========================================
Total SNI Captures: $total_captures
Unique Hostnames: $unique_hostnames
JFR File Size: $jfr_size
Kafka Memory Usage: $memory_usage

Recent Captures (last 10):
$(grep "PROD-SNI.*SNI CAPTURED" "$LOG_FILE" | tail -10 | awk '{print $1, $2, $6}')
EOF
```

## Troubleshooting Common Issues

### Issue 1: Agent Not Loading

#### Symptoms
```bash
# No agent startup messages in logs
grep "PROD-SNI" /var/log/kafka/server.log
# Returns empty
```

#### Diagnosis
```bash
# Check KAFKA_OPTS environment
echo $KAFKA_OPTS | grep javaagent

# Verify JAR file exists and permissions
ls -la /opt/kafka/lib/sni-jfr-agent-1.0.0.jar

# Check Java version (requires Java 17+)
java -version

# Verify Kafka startup command
ps aux | grep kafka | grep javaagent
```

#### Solution
```bash
# 1. Verify agent JAR path
export KAFKA_OPTS="$KAFKA_OPTS -javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar"

# 2. Check file permissions
chmod 644 /opt/kafka/lib/sni-jfr-agent-1.0.0.jar

# 3. Restart Kafka with verbose JVM options
export KAFKA_OPTS="$KAFKA_OPTS -verbose:class"
# Look for: [Loaded com.kafka.jfr.sni.ProductionSNIAgent]
```

### Issue 2: No SNI Captures

#### Symptoms
```bash
# Agent loads but no captures
grep "PROD-SNI.*SNI CAPTURED" /var/log/kafka/server.log
# Returns empty

# Or JFR shows no custom events
jfr print --events kafka.sni.Handshake /var/log/kafka/kafka-sni-capture.jfr
# No output
```

#### Diagnosis
```bash
# 1. Check if SSL connections are being made
netstat -an | grep :9093 | grep ESTABLISHED

# 2. Verify SSL configuration
grep -i ssl /opt/kafka/config/server.properties

# 3. Check if clients are sending SNI
# Enable debug mode temporarily
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=true"

# 4. Test with known SNI client
openssl s_client -connect kafka-server:9093 -servername test-hostname
```

#### Solution
```bash
# 1. Ensure SSL is properly configured
# Check server.properties for:
listeners=SSL://0.0.0.0:9093
security.inter.broker.protocol=SSL
ssl.keystore.location=/path/to/keystore.jks

# 2. Test with explicit SNI client
KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar -Dsni.jfr.debug=true" \
kafka-topics --bootstrap-server kafka.example.com:9093 --list

# 3. Verify client configuration includes hostname
bootstrap.servers=kafka.example.com:9093  # Not IP address
```

### Issue 3: JFR File Issues

#### Symptoms
```bash
# JFR file missing or empty
ls -la /var/log/kafka/kafka-sni-capture.jfr
# File not found or 0 bytes

# Or JFR file corrupted
jfr summary /var/log/kafka/kafka-sni-capture.jfr
# Error: Could not parse JFR file
```

#### Diagnosis
```bash
# 1. Check file permissions and ownership
ls -la /var/log/kafka/

# 2. Check disk space
df -h /var/log/kafka/

# 3. Verify JFR configuration
grep "PROD-SNI.*JFR recording started" /var/log/kafka/server.log

# 4. Check for JFR errors
grep -i "jfr\|recording" /var/log/kafka/server.log | grep -i error
```

#### Solution
```bash
# 1. Fix permissions
sudo chown kafka:kafka /var/log/kafka/
sudo chmod 755 /var/log/kafka/

# 2. Ensure sufficient disk space
# JFR files can grow large - ensure at least 1GB free

# 3. Restart with fresh JFR file
rm -f /var/log/kafka/kafka-sni-capture.jfr
# Restart Kafka service

# 4. Test with minimal configuration
export KAFKA_OPTS="-javaagent:/opt/kafka/lib/sni-jfr-agent-1.0.0.jar -Dsni.jfr.output=./test.jfr"
```

### Issue 4: Performance Impact

#### Symptoms
```bash
# High CPU usage or memory consumption
top -p $(pgrep -f kafka.Kafka)

# Or increased GC activity
grep "GC" /var/log/kafka/server.log | tail -20
```

#### Diagnosis
```bash
# 1. Monitor agent overhead
# Check capture rate vs. connections
capture_rate=$(grep -c "SNI CAPTURED" /var/log/kafka/server.log)
connection_count=$(netstat -an | grep :9093 | grep ESTABLISHED | wc -l)

# 2. Check JFR file size growth rate
watch -n 30 'ls -lah /var/log/kafka/kafka-sni-capture.jfr'

# 3. Monitor memory allocation
jstat -gc $(pgrep -f kafka.Kafka) 5s
```

#### Solution
```bash
# 1. Reduce JFR buffer size
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.maxSize=50000000"  # 50MB

# 2. Disable debug logging
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=false"

# 3. Increase heap if needed
export KAFKA_HEAP_OPTS="-Xmx6g -Xms6g"

# 4. Adjust JFR settings
export KAFKA_OPTS="$KAFKA_OPTS -XX:FlushInterval=120"  # Flush every 2 minutes
```

## Alerting Configuration

### 1. Nagios Check
```bash
#!/bin/bash
# /usr/local/nagios/libexec/check_sni_agent.sh

CRITICAL=2
WARNING=1
OK=0

# Check if agent is capturing SNI
if ! grep -q "PROD-SNI.*SNI CAPTURED" /var/log/kafka/server.log; then
    echo "CRITICAL: No SNI captures detected"
    exit $CRITICAL
fi

# Check JFR file age (should be updated recently)
JFR_FILE="/var/log/kafka/kafka-sni-capture.jfr"
if [ $(find "$JFR_FILE" -mmin +60 | wc -l) -gt 0 ]; then
    echo "WARNING: JFR file not updated in 60 minutes"
    exit $WARNING
fi

echo "OK: SNI agent functioning normally"
exit $OK
```

### 2. Prometheus Alerting Rules
```yaml
# sni-agent-alerts.yml
groups:
- name: sni-agent
  rules:
  - alert: SNIAgentDown
    expr: up{job="kafka-sni"} == 0
    for: 5m
    annotations:
      summary: "SNI capture agent is down"

  - alert: NoSNICaptures
    expr: increase(sni_captures_total[10m]) == 0
    for: 10m
    annotations:
      summary: "No SNI captures in last 10 minutes"

  - alert: JFRFileLarge
    expr: kafka_jfr_file_size_bytes > 200000000
    annotations:
      summary: "JFR file size exceeding 200MB"
```

## Performance Optimization

### 1. JVM Tuning for SNI Agent
```bash
# Optimized JVM settings for Kafka with SNI agent
export KAFKA_JVM_PERFORMANCE_OPTS="
  -server
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=20
  -XX:InitiatingHeapOccupancyPercent=35
  -XX:+ExplicitGCInvokesConcurrent
  -XX:MaxInlineLevel=15
  -XX:+UseStringDeduplication
  -Djava.awt.headless=true
  -Dcom.sun.management.jmxremote=true"
```

### 2. Agent Configuration Tuning
```bash
# High-throughput environment settings
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.debug=false"           # Disable debug
export KAFKA_OPTS="$KAFKA_OPTS -Dsni.jfr.maxSize=500000000"     # 500MB buffer
export KAFKA_OPTS="$KAFKA_OPTS -XX:FlushInterval=300"           # 5-minute flush
```

This comprehensive monitoring and troubleshooting guide ensures reliable operation and quick resolution of issues with the SNI JFR capture agent.