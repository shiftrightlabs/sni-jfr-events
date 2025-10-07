package io.github.shiftrightlabs.sni.jfr;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for JMC Agent + SSL Converter with Kafka using Testcontainers.
 * <p>
 * This test:
 * 1. Generates SSL certificates with same CA for server and client
 * 2. Starts Kafka broker with client authentication enabled
 * 3. Connects Kafka producer with client certificate and SNI hostname
 * 4. Verifies JFR events captured both SNI hostname and client certificate CN
 * <p>
 * Certificate structure:
 * - CA Certificate: CN=Test CA (used to sign both server and client certs)
 * - Server Certificate: CN=kafka-broker.test.local, SANs=[kafka-broker.test.local, kafka-alias.test.local]
 * - Client Certificate: CN=kafka-client.test.local
 * - Both server and client trust the same CA
 * <p>
 * The test uses a two-hostname approach for SNI:
 * - Server primary hostname: kafka-broker.test.local
 * - Client connects to alias: kafka-alias.test.local (triggers SNI extension)
 * - Both hostnames resolve to the same container in Docker network
 */
@Testcontainers
public class KafkaJMCAgentE2ETest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaJMCAgentE2ETest.class);

    private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.7";
    private static final String CONVERTER_JAR_PATH = "target/kafka-ssl-jfr-1.1.0.jar";
    private static final String JMC_AGENT_JAR_PATH = "target/test-libs/jmc-agent.jar";

    // Two-hostname approach for SNI testing
    private static final String BROKER_CN = "kafka-broker.test.local";
    private static final String CLIENT_ALIAS = "kafka-alias.test.local";
    private static final String CLIENT_CN = "kafka-client.test.local";
    private static final String PASSWORD = "password123";

    @TempDir
    static Path tempDir;

    private static Network network;
    private static GenericContainer<?> zookeeper;
    private static GenericContainer<?> kafka;
    private static Path converterJar;
    private static Path jmcAgentJar;
    private static Path keystorePath;
    private static Path clientKeystorePath;
    private static Path truststorePath;
    private static Path xmlConfigPath;
    private static Path credentialsPath;

    @BeforeAll
    static void setUp() throws Exception {
        // Verify converter JAR exists
        converterJar = Paths.get(CONVERTER_JAR_PATH).toAbsolutePath();
        if (!Files.exists(converterJar)) {
            LOG.warn("====================================");
            LOG.warn("SKIPPING TEST: Converter JAR not found at {}", converterJar);
            LOG.warn("Please run 'mvn clean package' first");
            LOG.warn("====================================");
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Converter JAR must be built first. Run: mvn clean package");
        }

        // Check if JMC Agent JAR exists
        jmcAgentJar = Paths.get(JMC_AGENT_JAR_PATH).toAbsolutePath();
        if (!Files.exists(jmcAgentJar)) {
            LOG.warn("====================================");
            LOG.warn("SKIPPING TEST: JMC Agent JAR not found at {}", jmcAgentJar);
            LOG.warn("Download from: https://jdk.java.net/jmc/");
            LOG.warn("Or build from: https://github.com/openjdk/jmc");
            LOG.warn("Place at: {}", jmcAgentJar);
            LOG.warn("====================================");
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "JMC Agent JAR required. Download or build it first.");
        }

        // Setup paths
        keystorePath = tempDir.resolve("kafka.keystore.jks");
        clientKeystorePath = tempDir.resolve("client.keystore.jks");
        truststorePath = tempDir.resolve("kafka.truststore.jks");
        xmlConfigPath = Paths.get("kafka-ssl-jfr.xml").toAbsolutePath();
        credentialsPath = tempDir.resolve("kafka-ssl-credentials");

        // Verify XML config exists
        if (!Files.exists(xmlConfigPath)) {
            throw new IllegalStateException("XML config not found: " + xmlConfigPath);
        }

        // Generate SSL certificates with SANs and client cert with same CA
        LOG.info("Generating SSL certificates with SANs and client cert...");
        generateCertificatesWithClientAuth();

        // Create SSL credentials file
        Files.write(credentialsPath, PASSWORD.getBytes());

        // Create network for containers
        network = Network.newNetwork();

        // Start Zookeeper
        LOG.info("Starting Zookeeper...");
        zookeeper = new GenericContainer<>(DockerImageName.parse("confluentinc/cp-zookeeper:7.7.0"))
            .withNetwork(network)
            .withNetworkAliases("zookeeper")
            .withExposedPorts(2181)
            .withEnv("ZOOKEEPER_CLIENT_PORT", "2181")
            .withEnv("ZOOKEEPER_TICK_TIME", "2000")
            .waitingFor(Wait.forLogMessage(".*binding to port.*", 1));
        zookeeper.start();

        // Build JFR and JMC Agent options
        String containerJfrPath = "/tmp/kafka-ssl.jfr";
        String jfrOpts = String.format(
            "-XX:StartFlightRecording=name=SSLTest,filename=%s,maxsize=100m,dumponexit=true " +
            "-javaagent:/agent/jmc-agent.jar=/agent/kafka-ssl-jfr.xml",
            containerJfrPath
        );

        // Start Kafka with SSL and JMC Agent
        LOG.info("Starting Kafka broker with JMC Agent...");
        kafka = new GenericContainer<>(DockerImageName.parse(KAFKA_IMAGE))
            .withNetwork(network)
            // Both hostnames resolve to the same container
            .withNetworkAliases(BROKER_CN, CLIENT_ALIAS, "kafka")
            .withExposedPorts(9092, 9093)
            // Kafka configuration
            .withEnv("KAFKA_BROKER_ID", "1")
            .withEnv("KAFKA_ZOOKEEPER_CONNECT", "zookeeper:2181")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,SSL://0.0.0.0:9093")
            .withEnv("KAFKA_ADVERTISED_LISTENERS",
                "PLAINTEXT://" + BROKER_CN + ":9092,SSL://" + BROKER_CN + ":9093")
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,SSL:SSL")
            .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
            // SSL configuration
            .withEnv("KAFKA_SSL_KEYSTORE_FILENAME", "kafka.keystore.jks")
            .withEnv("KAFKA_SSL_KEYSTORE_CREDENTIALS", "kafka-ssl-credentials")
            .withEnv("KAFKA_SSL_KEY_CREDENTIALS", "kafka-ssl-credentials")
            .withEnv("KAFKA_SSL_TRUSTSTORE_FILENAME", "kafka.truststore.jks")
            .withEnv("KAFKA_SSL_TRUSTSTORE_CREDENTIALS", "kafka-ssl-credentials")
            .withEnv("KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM", " ")
            .withEnv("KAFKA_SSL_CLIENT_AUTH", "required")
            // Other settings
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            // JMC Agent and JFR
            .withEnv("KAFKA_OPTS", jfrOpts)
            // Add converter JAR to classpath so JMC Agent can find it
            .withEnv("CLASSPATH", "/agent/kafka-ssl-jfr.jar")
            .waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=1\\] started.*", 1)
                .withStartupTimeout(java.time.Duration.ofMinutes(2)))
            // Copy files to container
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(jmcAgentJar),
                "/agent/jmc-agent.jar")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(converterJar),
                "/agent/kafka-ssl-jfr.jar")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(xmlConfigPath),
                "/agent/kafka-ssl-jfr.xml")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(keystorePath),
                "/etc/kafka/secrets/kafka.keystore.jks")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(truststorePath),
                "/etc/kafka/secrets/kafka.truststore.jks")
            .withCopyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(credentialsPath),
                "/etc/kafka/secrets/kafka-ssl-credentials")
            .withLogConsumer(frame -> {
                String log = frame.getUtf8String();
                // Log JMC Agent output
                if (log.contains("jmc") || log.contains("JMC") || log.contains("javaagent")) {
                    LOG.info("[KAFKA-JMC] {}", log.trim());
                }
                // Log SSL-related output
                if (log.contains("SSL") || log.contains("ssl") || log.contains("server_name")) {
                    LOG.info("[KAFKA-SSL] {}", log.trim());
                }
            });

        kafka.start();
        LOG.info("Kafka broker started successfully");

        // Verify agent setup
        verifyAgentSetup();
    }

    @AfterAll
    static void tearDown() {
        if (kafka != null) {
            kafka.stop();
        }
        if (zookeeper != null) {
            zookeeper.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static void verifyAgentSetup() throws Exception {
        LOG.info("Verifying JMC Agent setup in container...");

        var lsResult = kafka.execInContainer("ls", "-la", "/agent/");
        LOG.info("Agent directory contents:\n{}", lsResult.getStdout());

        var jfrCheckResult = kafka.execInContainer("jcmd", "1", "JFR.check");
        LOG.info("JFR status:\n{}", jfrCheckResult.getStdout());
    }

    @Test
    void testJMCAgentCapturesSNIFromKafkaConnections() throws Exception {
        LOG.info("=== Testing SSL Metadata Capture with JMC Agent ===");
        LOG.info("Certificate CN: {}", BROKER_CN);
        LOG.info("Client connects to: {}", CLIENT_ALIAS);
        LOG.info("Expected SSL metadata - SNI: {}, Client CN: {}", CLIENT_ALIAS, CLIENT_CN);

        // Create producer script to run inside container
        String producerScript = createProducerScript();
        Path scriptPath = tempDir.resolve("producer-script.sh");
        Files.write(scriptPath, producerScript.getBytes());

        // Run producer in container on same network
        try (var producerContainer = new GenericContainer<>(DockerImageName.parse(KAFKA_IMAGE))) {
            producerContainer
                .withNetwork(network)
                .withCommand("bash", "/tmp/producer-script.sh")
                .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(scriptPath),
                    "/tmp/producer-script.sh")
                .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(truststorePath),
                    "/tmp/kafka.truststore.jks")
                .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(clientKeystorePath),
                    "/tmp/client.keystore.jks")
                .withLogConsumer(frame -> LOG.info("[PRODUCER] {}", frame.getUtf8String().trim()));

            LOG.info("Starting producer container...");
            producerContainer.start();

            // Wait for producer to finish
            int maxWaitSeconds = 60;
            int waited = 0;
            while (producerContainer.isRunning() && waited < maxWaitSeconds) {
                Thread.sleep(1000);
                waited++;
            }

            LOG.info("Producer finished after {} seconds", waited);
        }

        // Wait for JFR to flush events
        Thread.sleep(3000);

        // Dump JFR recording
        LOG.info("Dumping JFR recording...");
        var dumpResult = kafka.execInContainer("jcmd", "1", "JFR.dump",
            "name=SSLTest", "filename=/tmp/final-kafka-ssl.jfr");
        LOG.info("JFR dump result: {}", dumpResult.getStdout());

        // Copy JFR file from container
        Path localJfrPath = tempDir.resolve("final-kafka-ssl.jfr");
        kafka.copyFileFromContainer("/tmp/final-kafka-ssl.jfr", localJfrPath.toString());

        assertThat(localJfrPath).exists();
        assertThat(Files.size(localJfrPath)).isGreaterThan(0);
        LOG.info("JFR file copied: {} ({} bytes)", localJfrPath, Files.size(localJfrPath));

        // Parse JFR events
        List<String> jfrEvents = parseJFRFile(localJfrPath);

        // Find kafka.ssl.Handshake events
        List<String> sslEvents = jfrEvents.stream()
            .filter(e -> e.contains("kafka.ssl.Handshake"))
            .collect(Collectors.toList());

        LOG.info("Found {} kafka.ssl.Handshake events", sslEvents.size());
        sslEvents.forEach(event -> LOG.info("SSL Event:\n{}", event));

        // Extract SNI hostnames and client cert CNs
        Set<String> capturedSNI = new HashSet<>();
        Set<String> capturedClientCNs = new HashSet<>();
        Pattern sniPattern = Pattern.compile("sniHostname\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Pattern clientCNPattern = Pattern.compile("clientCertCN\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

        for (String event : sslEvents) {
            Matcher sniMatcher = sniPattern.matcher(event);
            if (sniMatcher.find()) {
                String hostname = sniMatcher.group(1);
                capturedSNI.add(hostname);
                LOG.info("Extracted SNI: {}", hostname);
            } else {
                LOG.warn("No SNI match in event (first 200 chars): {}",
                    event.substring(0, Math.min(200, event.length())));
            }

            Matcher cnMatcher = clientCNPattern.matcher(event);
            if (cnMatcher.find()) {
                String clientCN = cnMatcher.group(1);
                capturedClientCNs.add(clientCN);
                LOG.info("Extracted client CN: {}", clientCN);
            }
        }

        // Assertions
        assertThat(sslEvents)
            .as("Must have kafka.ssl.Handshake events")
            .isNotEmpty();

        assertThat(capturedSNI)
            .as("Captured SNI must contain expected hostname: " + CLIENT_ALIAS)
            .contains(CLIENT_ALIAS);

        assertThat(capturedClientCNs)
            .as("Captured client CNs must contain expected CN: " + CLIENT_CN)
            .contains(CLIENT_CN);

        LOG.info("=== TEST SUCCESS ===");
        LOG.info("✓ Found {} SSL handshake events", sslEvents.size());
        LOG.info("✓ Captured SNI hostnames: {}", capturedSNI);
        LOG.info("✓ Expected SNI '{}' was captured", CLIENT_ALIAS);
        LOG.info("✓ Captured client CNs: {}", capturedClientCNs);
        LOG.info("✓ Expected client CN '{}' was captured", CLIENT_CN);

        // Log events in human-readable format
        LOG.info("=== SSL HANDSHAKE EVENTS (Human Readable) ===");
        for (int i = 0; i < sslEvents.size(); i++) {
            String event = sslEvents.get(i);
            LOG.info("Event #{}: SNI={}, ClientCN={}",
                i + 1,
                extractField(event, sniPattern),
                extractField(event, clientCNPattern));
        }

    }

    private String extractField(String event, Pattern pattern) {
        Matcher matcher = pattern.matcher(event);
        return matcher.find() ? matcher.group(1) : "N/A";
    }

    private String createProducerScript() {
        return String.format(
            "#!/bin/bash\n" +
            "cat > /tmp/producer.properties << 'EOF'\n" +
            "bootstrap.servers=%s:9093\n" +
            "security.protocol=SSL\n" +
            "ssl.truststore.location=/tmp/kafka.truststore.jks\n" +
            "ssl.truststore.password=%s\n" +
            "ssl.keystore.location=/tmp/client.keystore.jks\n" +
            "ssl.keystore.password=%s\n" +
            "ssl.key.password=%s\n" +
            "ssl.endpoint.identification.algorithm=\n" +
            "key.serializer=org.apache.kafka.common.serialization.StringSerializer\n" +
            "value.serializer=org.apache.kafka.common.serialization.StringSerializer\n" +
            "EOF\n" +
            "\n" +
            "echo \"Waiting for Kafka at %s:9093...\"\n" +
            "for i in {1..30}; do\n" +
            "    if timeout 2 bash -c \"cat </dev/null >/dev/tcp/%s/9093\" 2>/dev/null; then\n" +
            "        echo \"Kafka is reachable\"\n" +
            "        break\n" +
            "    fi\n" +
            "    sleep 2\n" +
            "done\n" +
            "\n" +
            "kafka-topics --bootstrap-server %s:9093 \\\n" +
            "    --command-config /tmp/producer.properties \\\n" +
            "    --create --topic test-sni-topic --partitions 1 --replication-factor 1 2>/dev/null || true\n" +
            "\n" +
            "echo \"Sending test messages to %s:9093\"\n" +
            "for i in {1..5}; do\n" +
            "    echo \"test-message-$i\" | kafka-console-producer \\\n" +
            "        --producer.config /tmp/producer.properties \\\n" +
            "        --topic test-sni-topic \\\n" +
            "        --bootstrap-server %s:9093\n" +
            "    sleep 1\n" +
            "done\n" +
            "echo \"Finished sending messages\"\n",
            CLIENT_ALIAS, PASSWORD, PASSWORD, PASSWORD, CLIENT_ALIAS, CLIENT_ALIAS, CLIENT_ALIAS, CLIENT_ALIAS, CLIENT_ALIAS);
    }

    private List<String> parseJFRFile(Path jfrPath) throws Exception {
        List<String> events = new ArrayList<>();

        if (!Files.exists(jfrPath)) {
            LOG.error("JFR file does not exist: {}", jfrPath);
            return events;
        }

        String javaHome = System.getProperty("java.home");
        Path jfrCommand = Paths.get(javaHome, "bin", "jfr");
        if (!Files.exists(jfrCommand)) {
            jfrCommand = Paths.get("jfr");
        }

        ProcessBuilder pb = new ProcessBuilder(
            jfrCommand.toString(), "print",
            "--events", "kafka.ssl.*,jdk.TLSHandshake",
            jfrPath.toString());

        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            StringBuilder eventBuilder = new StringBuilder();
            boolean inEvent = false;

            while ((line = reader.readLine()) != null) {
                if (line.matches("^[\\w.]+\\s*\\{\\s*$")) {
                    if (eventBuilder.length() > 0 && inEvent) {
                        events.add(eventBuilder.toString());
                    }
                    eventBuilder = new StringBuilder(line);
                    inEvent = true;
                } else if (inEvent) {
                    eventBuilder.append("\n").append(line);
                    if (line.trim().equals("}")) {
                        events.add(eventBuilder.toString());
                        eventBuilder = new StringBuilder();
                        inEvent = false;
                    }
                }
            }

            if (eventBuilder.length() > 0 && inEvent) {
                events.add(eventBuilder.toString());
            }

            process.waitFor(30, TimeUnit.SECONDS);
        }

        LOG.info("Parsed {} events from JFR file", events.size());
        return events;
    }

    private static void generateCertificatesWithClientAuth() throws Exception {
        LOG.info("Using keytool to generate certificates for maximum Java SSL compatibility");

        Path certDir = keystorePath.getParent();
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";

        // 1. Generate CA keystore and certificate (shared root CA)
        Path caKeystorePath = certDir.resolve("ca.keystore.jks");
        executeKeytool(keytool, "-genkeypair",
            "-alias", "ca",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "365",
            "-dname", "CN=Test CA,OU=Test,O=TestOrg,C=US",
            "-keystore", caKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-keypass", PASSWORD,
            "-ext", "bc:c",
            "-storetype", "JKS");
        LOG.info("✓ CA certificate generated");

        // 2. Export CA certificate to file
        Path caCertPath = certDir.resolve("ca-cert.pem");
        executeKeytool(keytool, "-exportcert",
            "-alias", "ca",
            "-keystore", caKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", caCertPath.toString(),
            "-rfc");
        LOG.info("✓ CA certificate exported");

        // 3. Generate server keystore and certificate
        executeKeytool(keytool, "-genkeypair",
            "-alias", "kafka",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "365",
            "-dname", "CN=" + BROKER_CN + ",OU=Server,O=TestOrg,C=US",
            "-keystore", keystorePath.toString(),
            "-storepass", PASSWORD,
            "-keypass", PASSWORD,
            "-ext", "san=dns:" + BROKER_CN + ",dns:" + CLIENT_ALIAS,
            "-storetype", "PKCS12");
        LOG.info("✓ Server key pair generated");

        // 4. Create CSR for server certificate
        Path serverCsrPath = certDir.resolve("server.csr");
        executeKeytool(keytool, "-certreq",
            "-alias", "kafka",
            "-keystore", keystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", serverCsrPath.toString());
        LOG.info("✓ Server CSR created");

        // 5. Sign server certificate with CA
        Path serverCertPath = certDir.resolve("server-cert.pem");
        executeKeytool(keytool, "-gencert",
            "-alias", "ca",
            "-keystore", caKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-infile", serverCsrPath.toString(),
            "-outfile", serverCertPath.toString(),
            "-ext", "san=dns:" + BROKER_CN + ",dns:" + CLIENT_ALIAS,
            "-validity", "365",
            "-rfc");
        LOG.info("✓ Server certificate signed by CA");

        // 6. Import CA certificate into server keystore
        executeKeytool(keytool, "-importcert",
            "-alias", "ca",
            "-keystore", keystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", caCertPath.toString(),
            "-noprompt");
        LOG.info("✓ CA certificate imported into server keystore");

        // 7. Import signed server certificate into server keystore
        executeKeytool(keytool, "-importcert",
            "-alias", "kafka",
            "-keystore", keystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", serverCertPath.toString(),
            "-noprompt");
        LOG.info("✓ Signed server certificate imported into server keystore");

        // 8. Generate client keystore and certificate
        executeKeytool(keytool, "-genkeypair",
            "-alias", "client",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "365",
            "-dname", "CN=" + CLIENT_CN + ",OU=Client,O=TestOrg,C=US",
            "-keystore", clientKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-keypass", PASSWORD,
            "-storetype", "PKCS12");
        LOG.info("✓ Client key pair generated");

        // 9. Create CSR for client certificate
        Path clientCsrPath = certDir.resolve("client.csr");
        executeKeytool(keytool, "-certreq",
            "-alias", "client",
            "-keystore", clientKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", clientCsrPath.toString());
        LOG.info("✓ Client CSR created");

        // 10. Sign client certificate with CA
        Path clientCertPath = certDir.resolve("client-cert.pem");
        executeKeytool(keytool, "-gencert",
            "-alias", "ca",
            "-keystore", caKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-infile", clientCsrPath.toString(),
            "-outfile", clientCertPath.toString(),
            "-validity", "365",
            "-rfc");
        LOG.info("✓ Client certificate signed by CA");

        // 11. Import CA certificate into client keystore
        executeKeytool(keytool, "-importcert",
            "-alias", "ca",
            "-keystore", clientKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", caCertPath.toString(),
            "-noprompt");
        LOG.info("✓ CA certificate imported into client keystore");

        // 12. Import signed client certificate into client keystore
        executeKeytool(keytool, "-importcert",
            "-alias", "client",
            "-keystore", clientKeystorePath.toString(),
            "-storepass", PASSWORD,
            "-file", clientCertPath.toString(),
            "-noprompt");
        LOG.info("✓ Signed client certificate imported into client keystore");

        // 13. Create shared truststore with CA certificate (both server and client use this)
        executeKeytool(keytool, "-importcert",
            "-alias", "ca",
            "-keystore", truststorePath.toString(),
            "-storepass", PASSWORD,
            "-file", caCertPath.toString(),
            "-noprompt",
            "-storetype", "JKS");
        LOG.info("✓ Shared truststore created with root CA");
        LOG.info("  - Server will use this to validate client certificates");
        LOG.info("  - Client will use this to validate server certificate");

        LOG.info("SSL certificates generated using keytool:");
        LOG.info("  CA: CN=Test CA");
        LOG.info("  Server: CN={}, SANs={}", BROKER_CN, Arrays.asList(BROKER_CN, CLIENT_ALIAS));
        LOG.info("  Client: CN={}", CLIENT_CN);
        LOG.info("  All certificates stored in: {}", certDir);
    }

    private static void executeKeytool(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("keytool command failed with exit code " + exitCode +
                "\nCommand: " + String.join(" ", command) +
                "\nOutput: " + output);
        }
    }

}
