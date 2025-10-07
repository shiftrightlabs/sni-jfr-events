# Manual Kafka Integration Test

This guide explains how to manually test the JMC Agent + SSL Converter integration with a real Kafka broker.

## Prerequisites

1. **Kafka broker** (version 3.0+) with SSL enabled
2. **JMC Agent JAR** - Automatically downloaded by Maven, or download manually from https://github.com/adoptium/jmc-build/releases
3. **Built converter JAR** - Run `mvn clean package`
4. **SSL certificates** - See below for generation steps

## Step 1: Generate SSL Certificates with Client Authentication

This guide uses the same certificate generation approach as the E2E tests, creating a shared CA and signing both server and client certificates.

```bash
# Set variables
CA_KEYSTORE="ca.keystore.jks"
SERVER_KEYSTORE="kafka.keystore.jks"
CLIENT_KEYSTORE="client.keystore.jks"
TRUSTSTORE="kafka.truststore.jks"
PASSWORD="changeit"

# Two-hostname approach for SNI testing
# Server certificate CN (primary hostname)
BROKER_CN="kafka-broker.test.local"
# Client connects to this alias (triggers SNI extension)
CLIENT_ALIAS="kafka-alias.test.local"
# Client certificate CN
CLIENT_CN="kafka-client.test.local"

# 1. Generate CA certificate
keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 \
    -dname "CN=Test CA,OU=Test,O=TestOrg,C=US" \
    -keystore "$CA_KEYSTORE" -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -validity 365 -ext bc:c -storetype JKS

# 2. Export CA certificate
keytool -exportcert -alias ca -keystore "$CA_KEYSTORE" -storepass "$PASSWORD" \
    -file ca-cert.pem -rfc

# 3. Generate server key pair (with both hostnames in SAN)
keytool -genkeypair -alias kafka -keyalg RSA -keysize 2048 \
    -dname "CN=$BROKER_CN,OU=Server,O=TestOrg,C=US" \
    -keystore "$SERVER_KEYSTORE" -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -validity 365 -ext "san=dns:$BROKER_CN,dns:$CLIENT_ALIAS" -storetype PKCS12

# 4. Create server CSR
keytool -certreq -alias kafka -keystore "$SERVER_KEYSTORE" -storepass "$PASSWORD" \
    -file server.csr

# 5. Sign server certificate with CA (with both hostnames in SAN)
keytool -gencert -alias ca -keystore "$CA_KEYSTORE" -storepass "$PASSWORD" \
    -infile server.csr -outfile server-cert.pem \
    -ext "san=dns:$BROKER_CN,dns:$CLIENT_ALIAS" -validity 365 -rfc

# 6. Import CA certificate into server keystore
keytool -importcert -alias ca -keystore "$SERVER_KEYSTORE" -storepass "$PASSWORD" \
    -file ca-cert.pem -noprompt

# 7. Import signed server certificate
keytool -importcert -alias kafka -keystore "$SERVER_KEYSTORE" -storepass "$PASSWORD" \
    -file server-cert.pem -noprompt

# 8. Generate client key pair
keytool -genkeypair -alias client -keyalg RSA -keysize 2048 \
    -dname "CN=$CLIENT_CN,OU=Client,O=TestOrg,C=US" \
    -keystore "$CLIENT_KEYSTORE" -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -validity 365 -storetype PKCS12

# 9. Create client CSR
keytool -certreq -alias client -keystore "$CLIENT_KEYSTORE" -storepass "$PASSWORD" \
    -file client.csr

# 10. Sign client certificate with CA
keytool -gencert -alias ca -keystore "$CA_KEYSTORE" -storepass "$PASSWORD" \
    -infile client.csr -outfile client-cert.pem -validity 365 -rfc

# 11. Import CA certificate into client keystore
keytool -importcert -alias ca -keystore "$CLIENT_KEYSTORE" -storepass "$PASSWORD" \
    -file ca-cert.pem -noprompt

# 12. Import signed client certificate
keytool -importcert -alias client -keystore "$CLIENT_KEYSTORE" -storepass "$PASSWORD" \
    -file client-cert.pem -noprompt

# 13. Create shared truststore (for both server and client)
keytool -importcert -alias ca -keystore "$TRUSTSTORE" -storepass "$PASSWORD" \
    -file ca-cert.pem -noprompt -storetype JKS

echo "✓ Certificates generated successfully"
```

## Step 2: Configure Kafka Broker for SSL with Client Authentication

Add to `server.properties`:

```properties
# SSL Listener
listeners=PLAINTEXT://localhost:9092,SSL://localhost:9093
advertised.listeners=PLAINTEXT://localhost:9092,SSL://localhost:9093
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,SSL:SSL

# SSL Configuration
ssl.keystore.location=/path/to/kafka.keystore.jks
ssl.keystore.password=changeit
ssl.key.password=changeit
ssl.truststore.location=/path/to/kafka.truststore.jks
ssl.truststore.password=changeit

# Enable client authentication (mTLS)
ssl.client.auth=required

# Disable hostname verification for testing
ssl.endpoint.identification.algorithm=
```

## Step 3: Start Kafka with JMC Agent

```bash
# Set CLASSPATH to include converter JAR
export CLASSPATH="/path/to/kafka-ssl-jfr-1.0.0.jar:$CLASSPATH"

# Set KAFKA_OPTS to load JMC Agent
export KAFKA_OPTS="\
  -javaagent:/path/to/jmc-agent.jar=/path/to/kafka-ssl-jfr.xml \
  -XX:StartFlightRecording=filename=kafka-ssl.jfr,dumponexit=true"

# Start Kafka broker
bin/kafka-server-start.sh config/server.properties
```

**Expected output:**
- Broker starts successfully
- No errors about JMC Agent or converter class not found
- JFR recording starts: `Started recording 1. The result will be written to: kafka-ssl.jfr`

## Step 4: Connect Kafka Client with SNI and Client Certificate

**Important:** To test SNI properly, the client connects to `CLIENT_ALIAS` (kafka-alias.test.local) while the broker certificate has CN=`BROKER_CN` (kafka-broker.test.local). This mismatch triggers the SNI extension.

Create a test producer (producer.properties):

```properties
# Connect to CLIENT_ALIAS to trigger SNI
bootstrap.servers=kafka-alias.test.local:9093
security.protocol=SSL

# Truststore (to validate broker certificate)
ssl.truststore.location=/path/to/kafka.truststore.jks
ssl.truststore.password=changeit

# Keystore (for client certificate authentication)
ssl.keystore.location=/path/to/client.keystore.jks
ssl.keystore.password=changeit
ssl.key.password=changeit

# Disable hostname verification for testing
ssl.endpoint.identification.algorithm=
```

Send a test message:

```bash
# Add both hostnames to /etc/hosts (both point to broker)
echo "127.0.0.1 kafka-broker.test.local kafka-alias.test.local" | sudo tee -a /etc/hosts

# Send test message (client connects to CLIENT_ALIAS)
echo "test-key:test-value" | bin/kafka-console-producer.sh \
    --bootstrap-server kafka-alias.test.local:9093 \
    --topic test-topic \
    --property "parse.key=true" \
    --property "key.separator=:" \
    --producer.config producer.properties
```

## Step 5: Verify JFR Events

After sending messages, stop the broker (this triggers JFR dump).

View captured SSL handshake events:

```bash
jfr print --events kafka.ssl.Handshake kafka-ssl.jfr
```

**Expected output:**

```
kafka.ssl.Handshake {
  startTime = 2025-10-07T08:30:15.123Z
  duration = 0.5 ms
  eventThread = "data-plane-kafka-network-thread-1-ListenerName(SSL)-SSL-2" (javaThreadId = 42)
  stackTrace = [
    org.apache.kafka.common.network.SslTransportLayer.handshakeFinished() line: 476
    org.apache.kafka.common.network.SslTransportLayer.handshake()
    ...
  ]
  sniHostname = "kafka-alias.test.local"
  clientCertCN = "kafka-client.test.local"
  channelId = "127.0.0.1:9093-127.0.0.1:54321-0"
}
```

**Key fields captured:**
- `sniHostname`: The hostname the client sent in SNI extension (kafka-alias.test.local)
- `clientCertCN`: The Common Name from the client certificate (kafka-client.test.local, requires mTLS)
- `channelId`: Kafka's connection identifier for correlation

**Why SNI is captured:**
- Client connects to `kafka-alias.test.local` (CLIENT_ALIAS)
- Server certificate has CN=`kafka-broker.test.local` (BROKER_CN)
- Both hostnames are in server certificate SAN, so SSL handshake succeeds
- Client sends SNI=`kafka-alias.test.local` in TLS ClientHello

## Step 6: Analyze Events

Export to JSON for analysis:

```bash
jfr print --json --events kafka.ssl.Handshake kafka-ssl.jfr > ssl-events.json
```

Count unique SNI values:

```bash
jfr print --events kafka.ssl.Handshake kafka-ssl.jfr | grep sniHostname | sort | uniq -c
```

Count unique client certificates:

```bash
jfr print --events kafka.ssl.Handshake kafka-ssl.jfr | grep clientCertCN | sort | uniq -c
```

## Troubleshooting

### Problem: ClassNotFoundException for SNIHostnameExtractor or ClientCertificateCNExtractor

**Cause:** Converter JAR not on classpath

**Solution:** Ensure converter JAR is added to `CLASSPATH` environment variable before starting Kafka:
```bash
export CLASSPATH="/path/to/kafka-ssl-jfr-1.0.0.jar:$CLASSPATH"
```

### Problem: No JFR events captured

**Causes:**
1. JMC Agent not loaded
2. XML configuration incorrect
3. Kafka version doesn't have `SslTransportLayer.handshakeFinished()` method

**Solutions:**
1. Check broker logs for JMC Agent initialization
2. Verify `kafka-ssl-jfr.xml` path is correct
3. Check Kafka version compatibility:
   ```bash
   javap -cp kafka-libs.jar org.apache.kafka.common.network.SslTransportLayer | grep handshakeFinished
   ```

### Problem: SNI hostname is null

**Causes:**
1. Client didn't send SNI (used IP address instead of hostname)
2. Client used `SSLEngine(host, port)` constructor without setting SNI parameters

**Solutions:**
1. Ensure client connects to hostname, not IP: `bootstrap.servers=kafka-alias.test.local:9093`
2. Verify SNI is sent: Enable debug logging `-Djavax.net.debug=ssl:handshake`
3. Check that client connects to CLIENT_ALIAS (not BROKER_CN) to trigger SNI

### Problem: ClientCertCN is null

**Causes:**
1. Client authentication not enabled (`ssl.client.auth=none`)
2. Client didn't provide certificate
3. Client certificate not signed by trusted CA

**Solutions:**
1. Set `ssl.client.auth=required` in broker config
2. Configure client keystore with valid certificate
3. Ensure client certificate is signed by CA in broker's truststore

### Problem: SSL handshake failure

**Error:** `PKIX path building failed: unable to find valid certification path`

**Causes:**
1. Certificates not signed by the same CA
2. CA certificate not in truststore
3. Certificate chain incomplete

**Solutions:**
1. Verify server certificate is signed by CA:
   ```bash
   keytool -list -v -keystore kafka.keystore.jks -storepass changeit
   ```
2. Verify CA is in truststore:
   ```bash
   keytool -list -v -keystore kafka.truststore.jks -storepass changeit
   ```
3. Ensure certificate chain includes CA certificate

## Validation Checklist

- [ ] Certificates generated with shared CA
- [ ] Server certificate includes proper SAN
- [ ] Client certificate signed by same CA
- [ ] Both keystores contain certificate chains
- [ ] Truststore contains CA certificate
- [ ] Broker starts without errors
- [ ] JFR recording initialized
- [ ] Client connects successfully over SSL with mTLS
- [ ] Messages produced/consumed successfully
- [ ] JFR file created on broker shutdown
- [ ] `kafka.ssl.Handshake` events present in JFR
- [ ] `sniHostname` field contains expected hostname
- [ ] `clientCertCN` field contains client certificate CN
- [ ] All connection metadata fields populated

## Performance Impact

Expected overhead with JMC Agent + JFR:
- **Startup:** +1-2% (bytecode instrumentation)
- **Runtime:** <0.1% (field access + method call)
- **JFR recording:** <1% (lock-free binary recording)

Compare to `-Djavax.net.debug=ssl`: **30-50% overhead**

## Cleanup

```bash
rm kafka-ssl.jfr
rm ca.keystore.jks kafka.keystore.jks client.keystore.jks kafka.truststore.jks
rm ca-cert.pem server-cert.pem client-cert.pem server.csr client.csr
sudo sed -i '/kafka-broker.test.local/d' /etc/hosts
sudo sed -i '/kafka-alias.test.local/d' /etc/hosts
```

## Automated Testing

For automated end-to-end testing with Docker and Testcontainers, see:
- [E2E-TEST-README.md](E2E-TEST-README.md) - Automated E2E testing guide
- Run with: `mvn test -Dtest=KafkaJMCAgentE2ETest`
