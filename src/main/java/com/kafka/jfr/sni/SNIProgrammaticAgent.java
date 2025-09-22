package com.kafka.jfr.sni;

import jdk.jfr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import javassist.*;
import java.security.ProtectionDomain;

/**
 * Agent that programmatically configures JFR after loading
 * This avoids the JFC file timing issue
 */
public class SNIProgrammaticAgent {

    private static final Logger log = LoggerFactory.getLogger(SNIProgrammaticAgent.class);

    // Keep recording alive for the entire JVM lifetime
    private static Recording globalRecording;
    private static Path outputFile = Paths.get("kafka-sni-programmatic.jfr");

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("Agent loading...");

        try {
            // Step 1: Register custom event class
            FlightRecorder.register(SNIHandshakeEvent.class);
            log.info("Custom event class registered");

            // Step 2: Create and start recording programmatically
            globalRecording = new Recording();
            globalRecording.setName("SNI-Monitoring");
            globalRecording.setDumpOnExit(true);
            globalRecording.setDestination(outputFile);
            globalRecording.setMaxSize(100_000_000); // 100MB
            globalRecording.setMaxAge(Duration.ofHours(1));

            // Step 3: Enable standard TLS events
            globalRecording.enable("jdk.TLSHandshake").withThreshold(Duration.ZERO);
            globalRecording.enable("jdk.X509Certificate");
            globalRecording.enable("jdk.X509Validation");

            // Step 4: Enable our custom event
            globalRecording.enable("kafka.sni.Handshake")
                .withThreshold(Duration.ZERO)
                .withoutStackTrace();  // Don't need stack traces for every event

            // Step 5: Start recording
            globalRecording.start();
            log.info("JFR recording started programmatically");
            log.info("Recording state: {}", globalRecording.getState());
            log.info("Output will be saved to: {}", outputFile.toAbsolutePath());

            // Step 6: Add shutdown hook to properly save recording
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (globalRecording != null && globalRecording.getState() == RecordingState.RUNNING) {
                        log.info("Shutdown hook triggered - dumping recording...");
                        globalRecording.dump(outputFile);
                        globalRecording.stop();
                        globalRecording.close();
                        log.info("Recording stopped and saved to: {}", outputFile.toAbsolutePath());
                    }
                } catch (Exception e) {
                    log.error("Error in shutdown hook", e);
                }
            }));

            // Step 7: Add instrumentation
            addInstrumentation(inst);

            // Step 8: Start monitoring thread
            startMonitoringThread();

        } catch (Exception e) {
            log.error("Failed to initialize", e);
        }
    }

    private static void addInstrumentation(Instrumentation inst) {
        // Add transformer to intercept SSL methods
        inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {

                // Target concrete SSL implementation classes and SNI-related classes
                if (className != null) {
                    // Target SSLParameters for SNI capture
                    if (className.equals("javax/net/ssl/SSLParameters")) {
                        log.info("Instrumenting SSLParameters");
                        return instrumentSSLParameters(className, classfileBuffer);
                    }

                    // Target Kafka SSL factory classes
                    if (className.contains("kafka") && className.contains("ssl")) {
                        log.debug("Found Kafka SSL class: {}", className);
                    }

                    // Skip SSLEngineImpl for now to avoid breaking SSL
                    // if (className.equals("sun/security/ssl/SSLEngineImpl")) {
                    //     System.out.println("[SNI-JFR] Instrumenting SSLEngineImpl");
                    //     return instrumentSSLEngineImpl(className, classfileBuffer);
                    // }

                    // Log SSL-related classes for debugging
                    if (className.contains("SNI") || className.contains("ServerName")) {
                        log.debug("Found SNI-related class: {}", className);
                    }
                }

                return classfileBuffer;
            }
        }, true);
    }

    private static byte[] instrumentSSLParameters(String className, byte[] classfileBuffer) {
        try {
            ClassPool pool = ClassPool.getDefault();
            pool.insertClassPath(new ByteArrayClassPath(className.replace('/', '.'), classfileBuffer));

            CtClass ctClass = pool.get(className.replace('/', '.'));

            // Instrument setServerNames to capture SNI being set
            try {
                CtMethod setServerNames = ctClass.getDeclaredMethod("setServerNames");
                // Insert safer code to capture SNI
                String code = "{ " +
                    "try { " +
                    "  if ($1 != null && !$1.isEmpty()) { " +
                    "    java.util.Iterator iter = $1.iterator(); " +
                    "    while (iter.hasNext()) { " +
                    "      Object obj = iter.next(); " +
                    "      if (obj instanceof javax.net.ssl.SNIServerName) { " +
                    "        javax.net.ssl.SNIServerName sn = (javax.net.ssl.SNIServerName)obj; " +
                    "        if (sn.getType() == 0) { " +
                    "          String hostname = new String(sn.getEncoded()); " +
                    "          com.kafka.jfr.sni.SNIProgrammaticAgent.captureSNI(hostname); " +
                    "        } " +
                    "      } " +
                    "    } " +
                    "  } " +
                    "} catch (Exception ex) { " +
                    "  org.slf4j.LoggerFactory.getLogger(\"SNIProgrammaticAgent\").error(\"Error in setServerNames: \" + ex); " +
                    "} " +
                    "}";
                setServerNames.insertBefore(code);
                log.info("Instrumented SSLParameters.setServerNames with error handling");
            } catch (NotFoundException e) {
                log.debug("setServerNames not found in SSLParameters");
            }

            byte[] byteCode = ctClass.toBytecode();
            ctClass.detach();
            return byteCode;

        } catch (Exception e) {
            log.error("Failed to instrument SSLParameters", e);
            return classfileBuffer;
        }
    }

    // Public static method to capture SNI from instrumented code
    public static void captureSNI(String sniHostname) {
        try {
            log.info("CAPTURED REAL SNI: {}", sniHostname);

            // Emit JFR event
            SNIHandshakeEvent event = new SNIHandshakeEvent();
            event.sniHostname = sniHostname;
            event.resolvedHost = ""; // Will be filled later during handshake
            event.connectionType = "CLIENT_SNI_CAPTURE";
            event.peerAddress = "";
            event.peerPort = 0;
            event.threadName = Thread.currentThread().getName();
            event.protocolVersion = "TLS";
            event.cipherSuite = "UNKNOWN";

            if (event.isEnabled()) {
                event.begin();
                event.commit();
                log.debug("REAL SNI event committed: {}", sniHostname);
            } else {
                log.warn("Event not enabled for: {}", sniHostname);
            }
        } catch (Exception e) {
            log.error("Error capturing SNI", e);
        }
    }

    // Capture SSL handshake details
    public static void captureHandshake(String peerHost, int peerPort, String protocol) {
        try {
            log.info("HANDSHAKE: {}:{} ({})", peerHost, peerPort, protocol);

            SNIHandshakeEvent event = new SNIHandshakeEvent();
            event.sniHostname = ""; // SNI was captured separately
            event.resolvedHost = peerHost;
            event.connectionType = "HANDSHAKE_INFO";
            event.peerAddress = peerHost;
            event.peerPort = peerPort;
            event.protocolVersion = protocol;
            event.threadName = Thread.currentThread().getName();

            if (event.isEnabled()) {
                event.begin();
                event.commit();
                log.debug("Handshake event committed: {}", peerHost);
            }
        } catch (Exception e) {
            log.error("Error capturing handshake", e);
        }
    }

    private static byte[] instrumentSSLEngineImpl(String className, byte[] classfileBuffer) {
        try {
            ClassPool pool = ClassPool.getDefault();
            pool.insertClassPath(new ByteArrayClassPath(className.replace('/', '.'), classfileBuffer));

            CtClass ctClass = pool.get(className.replace('/', '.'));

            // Instrument beginHandshake to capture handshake details
            try {
                CtMethod beginHandshake = ctClass.getDeclaredMethod("beginHandshake");
                String code = "{ " +
                    "String host = this.getPeerHost(); " +
                    "int port = this.getPeerPort(); " +
                    "if (host != null) { " +
                    "  com.kafka.jfr.sni.SNIProgrammaticAgent.captureHandshake(host, port, \"TLS\"); " +
                    "} " +
                    "}";
                beginHandshake.insertBefore(code);
                log.info("Instrumented SSLEngineImpl.beginHandshake");
            } catch (NotFoundException e) {
                log.debug("beginHandshake not found in SSLEngineImpl");
            }

            byte[] byteCode = ctClass.toBytecode();
            ctClass.detach();
            return byteCode;

        } catch (Exception e) {
            log.error("Failed to instrument SSLEngineImpl: {}", e);
            log.debug("Stack trace:", e);
            return classfileBuffer;
        }
    }

    private static void startMonitoringThread() {
        // Periodically emit test events and dump recording
        Thread monitorThread = new Thread(() -> {
            try {
                // Initial wait for Kafka to start
                Thread.sleep(5000);

                // Emit initial test event
                emitTestEvent("startup-test", "192.168.1.1");

                // Periodic monitoring and test events
                int counter = 0;
                while (true) {
                    Thread.sleep(10000);  // Every 10 seconds

                    // Emit periodic test event
                    counter++;
                    emitTestEvent("periodic-test-" + counter, "10.0.0." + counter);

                    // Periodically dump the recording to ensure data is saved
                    if (counter % 3 == 0 && globalRecording != null) {
                        try {
                            log.debug("Periodic dump at counter {}", counter);
                            globalRecording.dump(outputFile);
                            log.debug("Dumped to: {}", outputFile.toAbsolutePath());
                        } catch (Exception e) {
                            log.error("Dump error: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Monitor thread error: {}", e.getMessage());
                log.debug("Stack trace:", e);
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    // Helper method to emit test events
    private static void emitTestEvent(String hostname, String resolvedIp) {
        try {
            SNIHandshakeEvent event = new SNIHandshakeEvent();
            event.sniHostname = hostname;
            event.resolvedHost = resolvedIp;
            event.connectionType = "TEST_EVENT";
            event.peerAddress = "127.0.0.1";
            event.peerPort = 9093;
            event.protocolVersion = "TLSv1.3";
            event.cipherSuite = "TLS_AES_256_GCM_SHA384";
            event.threadName = Thread.currentThread().getName();
            event.handshakeDuration = 100;

            // Properly start and commit the event
            if (event.isEnabled()) {
                event.begin();
                // Simulate some work
                Thread.sleep(10);
                event.end();
                if (event.shouldCommit()) {
                    event.commit();
                    log.debug("Test event committed: {} -> {}", hostname, resolvedIp);
                }
            } else {
                log.warn("Event not enabled for: {}", hostname);
            }
        } catch (Exception e) {
            log.error("Failed to emit test event: {}", e.getMessage());
            log.debug("Stack trace:", e);
        }
    }
}