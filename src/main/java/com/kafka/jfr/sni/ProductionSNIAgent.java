package com.kafka.jfr.sni;

import jdk.jfr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javassist.*;
import java.security.ProtectionDomain;

/**
 * Production-ready SNI capture agent for Kafka JFR monitoring
 *
 * Features:
 * - Safe instrumentation with error handling
 * - Configurable JFR recording
 * - Performance monitoring
 * - Production logging
 */
public class ProductionSNIAgent {

    private static final Logger log = LoggerFactory.getLogger(ProductionSNIAgent.class);

    // Configuration
    private static final String JFR_OUTPUT_FILE = System.getProperty("sni.jfr.output", "kafka-sni-capture.jfr");
    private static final boolean ENABLE_DEBUG = Boolean.parseBoolean(System.getProperty("sni.jfr.debug", "false"));
    private static final long MAX_RECORDING_SIZE = Long.parseLong(System.getProperty("sni.jfr.maxSize", "100000000")); // 100MB

    // State management
    private static Recording globalRecording;
    private static final ConcurrentHashMap<String, String> sniMappings = new ConcurrentHashMap<>();
    private static final AtomicLong captureCount = new AtomicLong(0);
    private static volatile boolean agentInitialized = false;

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            log.info("Starting production SNI capture agent...");
            log.info("Configuration - Output: {}, Debug: {}", JFR_OUTPUT_FILE, ENABLE_DEBUG);

            // Register custom event
            FlightRecorder.register(SNIHandshakeEvent.class);
            log.info("Custom JFR event registered");

            // Setup JFR recording
            setupJFRRecording();

            // Add instrumentation
            addInstrumentation(inst);

            // Setup shutdown hook
            setupShutdownHook();

            // Start monitoring if debug enabled
            if (ENABLE_DEBUG) {
                startMonitoringThread();
            }

            agentInitialized = true;
            log.info("Production agent initialized successfully");

        } catch (Exception e) {
            log.error("FATAL: Failed to initialize agent", e);
        }
    }

    private static void setupJFRRecording() throws Exception {
        globalRecording = new Recording();
        globalRecording.setName("ProductionSNICapture");
        globalRecording.setDestination(Paths.get(JFR_OUTPUT_FILE));
        globalRecording.setMaxSize(MAX_RECORDING_SIZE);
        globalRecording.setMaxAge(Duration.ofHours(24));
        globalRecording.setDumpOnExit(true);

        // Enable standard TLS events for correlation
        globalRecording.enable("jdk.TLSHandshake").withThreshold(Duration.ZERO);
        globalRecording.enable("jdk.X509Certificate");

        // Enable our custom event
        globalRecording.enable("kafka.sni.Handshake")
            .withThreshold(Duration.ZERO)
            .withoutStackTrace();

        globalRecording.start();
        log.info("JFR recording started: {}", JFR_OUTPUT_FILE);
    }

    private static void addInstrumentation(Instrumentation inst) {
        inst.addTransformer(new java.lang.instrument.ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {

                if (className != null && className.equals("javax/net/ssl/SSLParameters")) {
                    log.debug("Instrumenting SSLParameters");
                    return instrumentSSLParameters(className, classfileBuffer);
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

            try {
                CtMethod setServerNames = ctClass.getDeclaredMethod("setServerNames");

                // Production-safe instrumentation with reflection
                String code = "{ " +
                    "if ($1 != null && !$1.isEmpty()) { " +
                    "  try { " +
                    "    java.util.Iterator iter = $1.iterator(); " +
                    "    while (iter.hasNext()) { " +
                    "      Object obj = iter.next(); " +
                    "      if (obj instanceof javax.net.ssl.SNIServerName) { " +
                    "        javax.net.ssl.SNIServerName sn = (javax.net.ssl.SNIServerName)obj; " +
                    "        if (sn.getType() == 0) { " +
                    "          String hostname = new String(sn.getEncoded()); " +
                    "          try { " +
                    "            Class agentClass = Class.forName(\"com.kafka.jfr.sni.ProductionSNIAgent\"); " +
                    "            java.lang.reflect.Method captureMethod = agentClass.getMethod(\"captureSNI\", String.class); " +
                    "            captureMethod.invoke(null, hostname); " +
                    "          } catch (Exception ex) { } " +
                    "        } " +
                    "      } " +
                    "    } " +
                    "  } catch (Exception ex) { } " +
                    "} " +
                    "}";

                setServerNames.insertBefore(code);
                log.info("SSLParameters.setServerNames instrumented");

            } catch (NotFoundException e) {
                log.debug("setServerNames method not found");
            }

            byte[] byteCode = ctClass.toBytecode();
            ctClass.detach();
            return byteCode;

        } catch (Exception e) {
            log.error("Instrumentation error: {}", e.getMessage());
            return classfileBuffer;
        }
    }

    /**
     * Production method to capture SNI - called via reflection from instrumented code
     */
    public static void captureSNI(String sniHostname) {
        if (!agentInitialized || sniHostname == null || sniHostname.trim().isEmpty()) {
            return;
        }

        try {
            captureCount.incrementAndGet();
            sniMappings.put(Thread.currentThread().getName(), sniHostname);

            log.info("SNI CAPTURED: {} (count: {})", sniHostname, captureCount.get());

            // Emit JFR event
            SNIHandshakeEvent event = new SNIHandshakeEvent();
            event.sniHostname = sniHostname;
            event.resolvedHost = "";
            event.connectionType = "PRODUCTION_SNI_CAPTURE";
            event.threadName = Thread.currentThread().getName();
            event.protocolVersion = "TLS";

            if (event.isEnabled()) {
                event.begin();
                event.commit();
                log.debug("JFR event committed for: {}", sniHostname);
            }

        } catch (Exception e) {
            log.debug("Error in captureSNI: {}", e.getMessage());
        }
    }

    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown initiated - captured {} SNI values", captureCount.get());

                if (globalRecording != null && globalRecording.getState() == RecordingState.RUNNING) {
                    globalRecording.dump(Paths.get(JFR_OUTPUT_FILE));
                    globalRecording.stop();
                    globalRecording.close();
                    log.info("Recording saved to: {}", JFR_OUTPUT_FILE);
                }

                // Print summary
                if (!sniMappings.isEmpty()) {
                    log.info("Captured SNI hostnames: {}", sniMappings.values());
                }

            } catch (Exception e) {
                log.error("Shutdown error", e);
            }
        }));
    }

    private static void startMonitoringThread() {
        Thread monitor = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(30000); // Every 30 seconds
                    log.info("Status - Total captures: {}, Active mappings: {}",
                        captureCount.get(), sniMappings.size());

                    // Periodic dump for long-running processes
                    if (globalRecording != null) {
                        globalRecording.dump(Paths.get(JFR_OUTPUT_FILE));
                    }
                }
            } catch (InterruptedException e) {
                log.info("Monitoring thread stopped");
            } catch (Exception e) {
                log.debug("Monitor error: {}", e);
            }
        });
        monitor.setDaemon(true);
        monitor.setName("SNI-Monitor");
        monitor.start();
        log.debug("Monitoring thread started");
    }

    // Logging is now handled by SLF4J

    // Public API for metrics
    public static long getCaptureCount() {
        return captureCount.get();
    }

    public static boolean isInitialized() {
        return agentInitialized;
    }
}