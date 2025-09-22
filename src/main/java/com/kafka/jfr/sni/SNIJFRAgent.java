package com.kafka.jfr.sni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import javassist.*;
import javassist.bytecode.Descriptor;

public class SNIJFRAgent {

    private static final Logger log = LoggerFactory.getLogger(SNIJFRAgent.class);

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("Starting SNI JFR Agent...");
        inst.addTransformer(new SSLEngineTransformer());
        inst.addTransformer(new SSLSocketTransformer());
    }

    static class SSLEngineTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            if ("sun/security/ssl/SSLEngineImpl".equals(className)) {
                log.info("Instrumenting SSLEngineImpl");
                return instrumentSSLEngine(classfileBuffer);
            }
            return classfileBuffer;
        }

        private byte[] instrumentSSLEngine(byte[] classfileBuffer) {
            try {
                ClassPool pool = ClassPool.getDefault();
                pool.insertClassPath(new ByteArrayClassPath("sun.security.ssl.SSLEngineImpl", classfileBuffer));

                CtClass ctClass = pool.get("sun.security.ssl.SSLEngineImpl");

                // Instrument beginHandshake method
                CtMethod beginHandshake = ctClass.getDeclaredMethod("beginHandshake");
                beginHandshake.insertBefore(
                    "com.kafka.jfr.sni.SNIEventEmitter.recordHandshakeStart(this);"
                );

                // Instrument wrap method (client sends data)
                CtMethod wrap = ctClass.getDeclaredMethod("wrap",
                    new CtClass[]{
                        pool.get("java.nio.ByteBuffer[]"),
                        pool.get("int"),
                        pool.get("int"),
                        pool.get("java.nio.ByteBuffer")
                    }
                );
                wrap.insertAfter(
                    "com.kafka.jfr.sni.SNIEventEmitter.checkClientHello(this, $4);"
                );

                return ctClass.toBytecode();
            } catch (Exception e) {
                log.error("Failed to instrument SSLEngineImpl: {}", e.getMessage());
                log.debug("Stack trace:", e);
                return classfileBuffer;
            }
        }
    }

    static class SSLSocketTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            if ("sun/security/ssl/SSLSocketImpl".equals(className)) {
                log.info("Instrumenting SSLSocketImpl");
                return instrumentSSLSocket(classfileBuffer);
            }
            return classfileBuffer;
        }

        private byte[] instrumentSSLSocket(byte[] classfileBuffer) {
            try {
                ClassPool pool = ClassPool.getDefault();
                pool.insertClassPath(new ByteArrayClassPath("sun.security.ssl.SSLSocketImpl", classfileBuffer));

                CtClass ctClass = pool.get("sun.security.ssl.SSLSocketImpl");

                // Instrument startHandshake method
                CtMethod startHandshake = ctClass.getDeclaredMethod("startHandshake");
                startHandshake.insertBefore(
                    "com.kafka.jfr.sni.SNIEventEmitter.recordSocketHandshake(this);"
                );

                return ctClass.toBytecode();
            } catch (Exception e) {
                log.error("Failed to instrument SSLSocketImpl: {}", e.getMessage());
                log.debug("Stack trace:", e);
                return classfileBuffer;
            }
        }
    }
}