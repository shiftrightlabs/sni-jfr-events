# End-to-End Testing with Testcontainers

## Overview

The `KafkaJMCAgentE2ETest` provides comprehensive end-to-end testing of the JMC Agent + SSL/TLS metadata converter integration with Kafka using Testcontainers. This test verifies that the converter successfully captures both SNI hostnames and client certificate CNs from SSL/TLS handshakes.

## Test Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Docker Network                            │
│                                                                │
│  ┌───────────────┐         ┌──────────────────────────────┐  │
│  │  Zookeeper    │◄────────┤  Kafka Broker Container      │  │
│  │  Container    │         │                              │  │
│  └───────────────┘         │  - SSL Listener on :9093     │  │
│                            │  - JMC Agent loaded          │  │
│                            │  - Extractors on classpath   │  │
│  ┌───────────────┐         │  - JFR recording enabled     │  │
│  │  Producer     │────────►│                              │  │
│  │  Container    │         │  Hostnames:                  │  │
│  │               │         │  - kafka-broker.test.local   │  │
│  │  Connects to: │         │  - kafka-alias.test.local    │  │
│  │  kafka-alias  │         │    (both resolve to same)    │  │
│  │  :9093        │         └──────────────────────────────┘  │
│  └───────────────┘                                            │
│                                                                │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
                     JFR File Extracted
                     kafka.ssl.Handshake Events
                     sniHostname = "kafka-alias.test.local"
                     clientCertCN = "kafka-client.test.local"
```

## Prerequisites

### 1. Build the Converter JAR

```bash
mvn clean package
```

This creates `target/kafka-ssl-jfr-1.1.0.jar`

### 2. JMC Agent JAR (Auto-Downloaded)

The JMC Agent JAR is automatically downloaded during the Maven build process via the `download-maven-plugin`.

**Download URL:** https://github.com/adoptium/jmc-build/releases/download/8.3.0/agent-1.0.1.jar
**Location:** `target/test-libs/jmc-agent.jar`

The JAR is downloaded during the `process-test-resources` phase, so it's available before tests run.

**Manual Download (if needed):**

```bash
mkdir -p target/test-libs
wget https://github.com/adoptium/jmc-build/releases/download/8.3.0/agent-1.0.1.jar -O target/test-libs/jmc-agent.jar
```

### 3. Docker

Ensure Docker is running, as Testcontainers requires it.

```bash
docker --version
```

## Running the E2E Test

### Run Single E2E Test

```bash
mvn test -Dtest=KafkaJMCAgentE2ETest
```

### Run All Tests (including E2E)

```bash
mvn test
```

**Note:** If JMC Agent JAR is not present, the test will be skipped with a message explaining how to obtain it.

## Test Scenarios

### Test 1: SSL Metadata Capture with Client Authentication (mTLS)

**Purpose:** Verify that JMC Agent captures SSL/TLS metadata (SNI hostnames and client certificate CN) from Kafka SSL connections with mutual TLS authentication

**Setup:**
- **Server Certificate:** CN=`kafka-broker.test.local`, SANs=[`kafka-broker.test.local`, `kafka-alias.test.local`]
- **Client Certificate:** CN=`kafka-client.test.local`
- **CA:** Shared root CA signs both server and client certificates
- **Client connects to:** `kafka-alias.test.local:9093` (triggers SNI extension)
- **Kafka broker:** Requires client authentication (`ssl.client.auth=required`)

**Expected Behavior:**
1. Kafka broker starts with JMC Agent loaded and client auth enabled
2. Producer connects using alias hostname with client certificate
3. SSL handshake completes with mutual authentication
4. JFR events captured: `kafka.ssl.Handshake`
5. Event fields populated:
   - `sniHostname` = `kafka-alias.test.local`
   - `clientCertCN` = `kafka-client.test.local`
   - `channelId` = Kafka's internal channel identifier

**Assertions:**
- ✓ JFR file created and non-empty
- ✓ `kafka.ssl.Handshake` events present
- ✓ `sniHostname` field contains `kafka-alias.test.local`
- ✓ `clientCertCN` field contains `kafka-client.test.local`
- ✓ `channelId` field populated

## Test Execution Flow

1. **Setup Phase** (`@BeforeAll`):
   - Verify converter JAR exists at `target/kafka-ssl-jfr-1.1.0.jar`
   - Verify JMC Agent JAR exists at `target/test-libs/jmc-agent.jar` (skip test if missing)
   - Generate SSL certificates using `keytool`:
     - Create CA certificate (CN=Test CA)
     - Generate server certificate (CN=kafka-broker.test.local, SANs=[kafka-broker.test.local, kafka-alias.test.local])
     - Generate client certificate (CN=kafka-client.test.local)
     - Sign both certificates with the same CA
     - Create shared truststore with CA certificate
   - Start Zookeeper container (confluentinc/cp-zookeeper:7.7.0)
   - Start Kafka container (confluentinc/cp-kafka:7.6.7) with:
     - JMC Agent configured with JFR recording
     - Converter JAR on classpath (`CLASSPATH=/agent/kafka-ssl-jfr.jar`)
     - SSL listener on port 9093
     - Client authentication required (`KAFKA_SSL_CLIENT_AUTH=required`)
     - Both hostnames (kafka-broker, kafka-alias) as network aliases

2. **Test Phase**:
   - Create producer script with SSL configuration and client certificate
   - Start producer container on same network (uses Kafka image for CLI tools)
   - Producer sends 5 test messages to `kafka-alias.test.local:9093`
   - Wait for producer to finish
   - Wait 3 seconds for JFR to flush events
   - Dump JFR recording from Kafka container (`jcmd 1 JFR.dump`)
   - Copy JFR file from container to host

3. **Verification Phase**:
   - Parse JFR file using `jfr print --events kafka.ssl.*,jdk.TLSHandshake`
   - Extract `kafka.ssl.Handshake` events from output
   - Parse event fields using regex:
     - `sniHostname = "kafka-alias.test.local"`
     - `clientCertCN = "kafka-client.test.local"`
   - Assert expected values captured
   - Log human-readable event summary

4. **Teardown Phase** (`@AfterAll`):
   - Stop Kafka container
   - Stop Zookeeper container
   - Close Docker network

## Certificate Generation

The test generates certificates using **keytool** for maximum Java SSL compatibility:

**Certificate Authority (CA):**
- Algorithm: RSA 2048-bit
- CN: Test CA
- Used to sign both server and client certificates

**Server Certificate:**
- Algorithm: RSA 2048-bit (stored in PKCS12 keystore)
- CN: kafka-broker.test.local
- SANs: dns:kafka-broker.test.local, dns:kafka-alias.test.local
- Signed by: Test CA

**Client Certificate:**
- Algorithm: RSA 2048-bit (stored in PKCS12 keystore)
- CN: kafka-client.test.local
- Signed by: Test CA

**Files Generated:**
- `kafka.keystore.jks` - Server keystore (contains server private key and certificate chain)
- `client.keystore.jks` - Client keystore (contains client private key and certificate chain)
- `kafka.truststore.jks` - Shared truststore (contains CA certificate for mutual trust)

## JMC Agent Configuration

The Kafka broker is started with:

```bash
-XX:StartFlightRecording=name=SSLTest,filename=/tmp/kafka-ssl.jfr,maxsize=100m,dumponexit=true
-javaagent:/agent/jmc-agent.jar=/agent/kafka-ssl-jfr.xml
```

And the converter on classpath via environment variable:

```bash
CLASSPATH=/agent/kafka-ssl-jfr.jar
```

**Files in Container:**
- `/agent/jmc-agent.jar` - JMC Agent
- `/agent/kafka-ssl-jfr.jar` - Our extractor classes (on classpath)
- `/agent/kafka-ssl-jfr.xml` - JMC Agent configuration

## Troubleshooting

### Test Skipped: Converter JAR Not Found

```
SKIPPING TEST: Converter JAR not found
Please run 'mvn clean package' first
```

**Solution:** Run `mvn clean package` to build the converter JAR

### Test Skipped: JMC Agent JAR Not Found

```
SKIPPING TEST: JMC Agent JAR not found at target/test-libs/jmc-agent.jar
Download from: https://jdk.java.net/jmc/
```

**Solution:** Follow one of the methods in "Obtain JMC Agent JAR" section above

### Docker Not Running

```
Could not find a valid Docker environment
```

**Solution:** Start Docker Desktop or Docker daemon

### Kafka Container Fails to Start

**Check logs:**

```bash
docker logs <container-id>
```

**Common causes:**
- SSL configuration errors
- JMC Agent JAR not found in container
- Converter JAR not on classpath
- XML configuration file missing

### No SSL Events Captured

**Possible causes:**

1. **JMC Agent not loaded:** Check Kafka logs for agent initialization
2. **Extractor classes not found:** Verify classpath includes converter JAR
3. **XML config incorrect:** Verify `kafka-ssl-jfr.xml` syntax
4. **No SSL connections:** Producer not using SSL protocol
5. **SNI not sent:** Client not using hostname (using IP instead)
6. **Client auth failure:** Client certificate not trusted by broker

**Debug steps:**

```bash
# Check JFR status in container
docker exec <kafka-container> jcmd 1 JFR.check

# Check classpath
docker exec <kafka-container> jcmd 1 VM.system_properties | grep java.class.path

# Check loaded agents
docker exec <kafka-container> jcmd 1 VM.command_line
```

### JFR File Empty or Corrupt

**Check JFR file:**

```bash
# Validate JFR file
jfr summary target/test-temp/final-kafka-ssl.jfr

# List events
jfr print --events kafka.ssl.* target/test-temp/final-kafka-ssl.jfr
```

## Test Performance

**Typical execution time:** 30-60 seconds

**Breakdown:**
- Container startup: 20-30s
- Message production: 5-10s
- JFR dump and copy: 2-5s
- Parsing and validation: 1-2s

## Integration with CI/CD

### GitHub Actions Example

```yaml
name: E2E Tests

on: [push, pull_request]

jobs:
  e2e-test:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'

    - name: Build converter JAR
      run: mvn clean package -DskipTests

    - name: Run E2E tests
      run: mvn test -Dtest=KafkaJMCAgentE2ETest
      # JMC Agent JAR is automatically downloaded by Maven

    - name: Upload JFR files
      if: always()
      uses: actions/upload-artifact@v3
      with:
        name: jfr-recordings
        path: target/test-temp/*.jfr
```

## Advanced Usage

### Custom Kafka Version

Modify `KAFKA_IMAGE` constant in test:

```java
private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.7";
```

### Enable Debug Logging

Add to `jfrOpts` in test:

```java
"-Djavax.net.debug=ssl:handshake"
```

### Custom JFR Settings

Create custom JFC file and mount:

```java
.withCopyFileToContainer(
    MountableFile.forHostPath(jfcConfigPath),
    "/tmp/custom.jfc")
```

Then use:

```java
"-XX:StartFlightRecording=settings=/tmp/custom.jfc,..."
```

## Validating Test Success

Successful test output:

```
[INFO] --- surefire:3.0.0:test (default-test) @ kafka-ssl-jfr ---
[INFO] Running io.github.shiftrightlabs.sni.jfr.KafkaJMCAgentE2ETest
[INFO] [KAFKA-JMC] JMC Agent loaded successfully
[INFO] [KAFKA-SSL] SSL Event: kafka-alias.test.local
[INFO] Found 5 kafka.ssl.Handshake events
[INFO] Extracted SNI: kafka-alias.test.local
[INFO] Extracted client CN: kafka-client.test.local
[INFO] === TEST SUCCESS ===
[INFO] ✓ Found 5 SSL handshake events
[INFO] ✓ Captured SNI hostnames: [kafka-alias.test.local]
[INFO] ✓ Expected SNI 'kafka-alias.test.local' was captured
[INFO] ✓ Captured client CNs: [kafka-client.test.local]
[INFO] ✓ Expected client CN 'kafka-client.test.local' was captured
[INFO] === SSL HANDSHAKE EVENTS (Human Readable) ===
[INFO] Event #1: SNI=kafka-alias.test.local, ClientCN=kafka-client.test.local
[INFO] Event #2: SNI=kafka-alias.test.local, ClientCN=kafka-client.test.local
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## References

- [Testcontainers Documentation](https://www.testcontainers.org/)
- [Testcontainers Kafka Module](https://www.testcontainers.org/modules/kafka/)
- [JMC Agent Documentation](https://github.com/openjdk/jmc/tree/master/agent)
- [Kafka SSL Configuration](https://kafka.apache.org/documentation/#security_ssl)
