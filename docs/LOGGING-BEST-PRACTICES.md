# 📝 Logging Best Practices for SNI Agent in Kafka Brokers

## Overview
This guide explains the improved logging approach using SLF4J for production Kafka broker deployments.

## 🔄 Logging Evolution

### Version 1: System.out (Not Recommended)
```java
System.out.println("[PROD-SNI] Starting production SNI capture agent...");
```
**Issues:**
- No log levels
- No timestamp
- No thread context
- Cannot be configured
- Poor performance
- Not integrated with Kafka logs

### Version 2: SLF4J Integration (Recommended)
```java
private static final Logger log = LoggerFactory.getLogger(ProductionSNIAgentV2.class);
log.info("Starting production SNI capture agent - version 2.0");
```
**Benefits:**
- ✅ Proper log levels (TRACE, DEBUG, INFO, WARN, ERROR)
- ✅ Integrated with Kafka's logging infrastructure
- ✅ Configurable via log4j.properties
- ✅ Better performance (async logging)
- ✅ MDC context for correlation
- ✅ Works with log aggregation tools

## 📊 Log Levels Strategy

### Production Settings
```properties
# Normal operation
log4j.logger.com.kafka.jfr.sni=INFO

# Troubleshooting
log4j.logger.com.kafka.jfr.sni=DEBUG
```

### Log Level Guidelines

| Level | Usage | Example |
|-------|-------|---------|
| **ERROR** | Agent failures, initialization errors | `Failed to initialize SNI capture agent` |
| **WARN** | Recoverable errors, anomalies | `Error capturing SNI for hostname` |
| **INFO** | Startup, shutdown, milestones | `Production SNI agent initialized successfully` |
| **DEBUG** | Detailed operation, statistics | `SNI captured: hostname=kafka.example.com` |
| **TRACE** | Very detailed debugging | `JFR event committed for SNI` |

## 🎯 Key Features of Improved Logging

### 1. MDC (Mapped Diagnostic Context)
```java
MDC.put("component", "SNI-AGENT");
MDC.put("sni", sniHostname);
```
**Benefits:**
- Automatic context in all log messages
- Easy filtering in log aggregation systems
- Thread-safe context propagation

### 2. Structured Logging
```java
log.info("SNI capture milestone: {} total captures, latest: {}", count, sniHostname);
```
**Benefits:**
- Machine-parseable format
- Efficient string formatting (lazy evaluation)
- Clear parameter separation

### 3. Performance Optimization
```java
if (log.isDebugEnabled()) {
    log.debug("Expensive operation: {}", computeExpensiveValue());
}
```
**Benefits:**
- Avoid expensive operations when not logging
- Reduce I/O overhead
- Async logging support

## 📋 Configuration Examples

### Basic Kafka Integration
```properties
# In Kafka's log4j.properties
log4j.logger.com.kafka.jfr.sni=INFO, kafkaAppender
```

### Separate SNI Log File
```properties
log4j.logger.com.kafka.jfr.sni=INFO, sniAppender
log4j.additivity.com.kafka.jfr.sni=false

log4j.appender.sniAppender=org.apache.log4j.RollingFileAppender
log4j.appender.sniAppender.File=${kafka.logs.dir}/sni-agent.log
log4j.appender.sniAppender.MaxFileSize=50MB
log4j.appender.sniAppender.MaxBackupIndex=10
```

### High-Performance Async Logging
```properties
log4j.appender.sniAsyncAppender=org.apache.log4j.AsyncAppender
log4j.appender.sniAsyncAppender.BufferSize=1000
log4j.appender.sniAsyncAppender.Blocking=false
```

## 🔍 Log Analysis Patterns

### Searching for SNI Captures
```bash
# All SNI captures
grep "SNI captured:" /var/log/kafka/sni-agent.log

# Specific hostname
grep "sni:kafka-client.example.com" /var/log/kafka/sni-agent.log

# Error analysis
grep "ERROR.*SNI" /var/log/kafka/server.log
```

### Log Parsing for Metrics
```bash
# Count unique SNI hostnames
grep "SNI captured:" sni-agent.log | \
  sed 's/.*hostname=\([^,]*\).*/\1/' | \
  sort | uniq -c | sort -rn

# Monitor error rate
grep -c "ERROR.*SNI" sni-agent.log
```

## 📈 Integration with Monitoring Systems

### ELK Stack (Elasticsearch, Logstash, Kibana)
```ruby
# Logstash pattern
filter {
  if [logger_name] == "com.kafka.jfr.sni.ProductionSNIAgentV2" {
    grok {
      match => {
        "message" => "SNI captured: hostname=%{DATA:sni_hostname}, thread=%{DATA:thread}, count=%{NUMBER:capture_count}"
      }
    }
  }
}
```

### Prometheus Metrics (via log parsing)
```yaml
# prometheus.yml
- job_name: 'sni_agent_logs'
  static_configs:
    - targets: ['log-exporter:9100']
  metric_relabel_configs:
    - source_labels: [sni_hostname]
      target_label: sni
```

### Splunk Search
```splunk
index=kafka source="/var/log/kafka/sni-agent.log"
| rex field=_raw "hostname=(?<sni>[^,]+)"
| stats count by sni
```

## 🚨 Troubleshooting with Logs

### Common Issues and Log Indicators

#### Agent Not Starting
```bash
# Check for initialization
grep "Starting production SNI capture agent" /var/log/kafka/server.log
grep "ERROR.*Failed to initialize" /var/log/kafka/server.log
```

#### No SNI Captures
```bash
# Enable debug logging
echo "log4j.logger.com.kafka.jfr.sni=DEBUG" >> /opt/kafka/config/log4j.properties
# Restart Kafka
# Check debug logs
grep "DEBUG.*Instrumenting SSLParameters" /var/log/kafka/server.log
```

#### Performance Issues
```bash
# Check capture rate
grep "SNI capture milestone" /var/log/kafka/sni-agent.log | \
  awk '{print $1, $2, $8}' | \
  tail -20
```

## 🎯 Best Practices Summary

### DO ✅
- Use SLF4J for all production logging
- Configure appropriate log levels per environment
- Use MDC for contextual information
- Implement structured logging with parameters
- Set up separate log files for high-volume scenarios
- Use async appenders for performance
- Include thread names and timestamps

### DON'T ❌
- Use System.out.println in production
- Log sensitive data (passwords, keys)
- Log at DEBUG/TRACE in production (unless troubleshooting)
- Perform expensive operations in log statements
- Ignore log rotation and retention
- Mix logging frameworks

## 📚 References

- [SLF4J Documentation](http://www.slf4j.org/manual.html)
- [Kafka Logging Configuration](https://kafka.apache.org/documentation/#brokerconfigs_log4j.properties)
- [Log4j Best Practices](https://logging.apache.org/log4j/1.2/manual.html)
- [MDC Usage Guide](http://logback.qos.ch/manual/mdc.html)

## 🔧 Migration Guide

To migrate from System.out to SLF4J:

1. **Update POM**: Add SLF4J dependency (provided scope)
2. **Update Code**: Replace System.out with Logger
3. **Configure log4j.properties**: Add agent-specific settings
4. **Test**: Verify logs appear in correct location
5. **Monitor**: Set up log aggregation and alerting

The improved logging approach ensures production-ready observability for the SNI capture agent in Kafka broker environments.