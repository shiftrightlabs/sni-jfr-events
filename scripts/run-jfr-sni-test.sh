#!/bin/bash

# Kafka JFR SNI/CN Test Script with Custom JFC Configuration
# Tests Kafka SSL/mTLS with SNI using JFR for low-overhead monitoring
# Hostname: kafka-broker.domaina.com

set -e

# Configuration
KAFKA_HOME="/Users/ansonau/kafka-playground/confluent-7.6.6"
KAFKA_PLAYGROUND="/Users/ansonau/kafka-playground"
HOSTNAME="kafka-broker.domaina.com"
PORT="9093"
JFC_FILE="$KAFKA_PLAYGROUND/custom-tls.jfc"
JFR_OUTPUT="kafka-jfr-test-$(date +%Y%m%d-%H%M%S).jfr"
EVIDENCE_FILE="jfr-test-evidence-$(date +%Y%m%d-%H%M%S).txt"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}======================================"
echo "Kafka JFR SNI/CN Test with Custom JFC"
echo "Hostname: $HOSTNAME"
echo "JFC Config: custom-tls.jfc"
echo "======================================${NC}"

# Function to cleanup processes
cleanup() {
    echo -e "\n${YELLOW}Cleaning up processes...${NC}"
    pkill -f kafka.Kafka 2>/dev/null || true
    pkill -f zookeeper 2>/dev/null || true
    rm -f /tmp/kafka-logs/.lock 2>/dev/null || true
    sleep 3
    echo "Cleanup complete"
}

# Function to check prerequisites
check_prerequisites() {
    echo -e "\n${GREEN}Step 1: Checking Prerequisites${NC}"

    # Check /etc/hosts
    if grep -q "$HOSTNAME" /etc/hosts; then
        echo "✅ $HOSTNAME found in /etc/hosts"
    else
        echo -e "${RED}❌ $HOSTNAME not found in /etc/hosts${NC}"
        echo "Please add: echo '127.0.0.1    $HOSTNAME' | sudo tee -a /etc/hosts"
        exit 1
    fi

    # Check certificates
    if [ ! -f "$KAFKA_PLAYGROUND/ssl/kafka.broker.keystore.jks" ]; then
        echo -e "${YELLOW}Certificates not found. Generating...${NC}"
        $KAFKA_PLAYGROUND/generate-kafka-certs-with-sans.sh
    else
        echo "✅ Certificates exist"
    fi

    # Check JFC file
    if [ ! -f "$JFC_FILE" ]; then
        echo -e "${RED}❌ Custom JFC file not found: $JFC_FILE${NC}"
        echo "Creating custom-tls.jfc..."
        create_jfc_config
    else
        echo "✅ Custom JFC configuration exists"
    fi

    # Check Kafka configuration
    if grep -q "advertised.listeners=SSL://$HOSTNAME:$PORT" $KAFKA_HOME/etc/kafka/server.properties; then
        echo "✅ Kafka configured correctly"
    else
        echo -e "${YELLOW}⚠️  Updating Kafka configuration...${NC}"
        # Ensure inter.broker.listener.name is set
        if ! grep -q "inter.broker.listener.name=SSL" $KAFKA_HOME/etc/kafka/server.properties; then
            echo "inter.broker.listener.name=SSL" >> $KAFKA_HOME/etc/kafka/server.properties
        fi
    fi
}

# Function to create JFC config if missing
create_jfc_config() {
    cat > "$JFC_FILE" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="Custom TLS Monitoring">
  <event name="jdk.TLSHandshake">
    <setting name="enabled">true</setting>
    <setting name="threshold">0 ms</setting>
    <setting name="stackTrace">true</setting>
  </event>
  <event name="jdk.X509Certificate">
    <setting name="enabled">true</setting>
    <setting name="threshold">0 ms</setting>
  </event>
  <event name="jdk.X509Validation">
    <setting name="enabled">true</setting>
  </event>
  <event name="jdk.SocketRead">
    <setting name="enabled">true</setting>
    <setting name="threshold">10 ms</setting>
  </event>
  <event name="jdk.SocketWrite">
    <setting name="enabled">true</setting>
    <setting name="threshold">10 ms</setting>
  </event>
</configuration>
EOF
    echo "✅ Created custom-tls.jfc"
}

# Start services
start_services() {
    echo -e "\n${GREEN}Step 2: Starting Services${NC}"

    # Start Zookeeper
    echo "Starting Zookeeper..."
    $KAFKA_HOME/bin/zookeeper-server-start -daemon $KAFKA_HOME/etc/kafka/zookeeper.properties
    sleep 5

    if pgrep -f "zookeeper" > /dev/null; then
        echo "✅ Zookeeper started"
    else
        echo -e "${RED}❌ Failed to start Zookeeper${NC}"
        exit 1
    fi

    # Start Kafka with JFR
    echo "Starting Kafka with JFR recording..."
    JFR_OPTS="-XX:StartFlightRecording=name=SNITest,settings=$JFC_FILE,filename=$JFR_OUTPUT,maxsize=100m,dumponexit=true"
    KAFKA_OPTS="$JFR_OPTS" $KAFKA_HOME/bin/kafka-server-start \
        $KAFKA_HOME/etc/kafka/server.properties > kafka-jfr.log 2>&1 &
    KAFKA_PID=$!

    echo "Kafka PID: $KAFKA_PID"
    sleep 10

    if ps -p $KAFKA_PID > /dev/null; then
        echo "✅ Kafka started with JFR recording"
    else
        echo -e "${RED}❌ Failed to start Kafka${NC}"
        tail -20 kafka-jfr.log
        exit 1
    fi
}

# Generate TLS traffic
generate_traffic() {
    echo -e "\n${GREEN}Step 3: Generating TLS Traffic${NC}"

    echo "=== JFR SNI/CN Test Evidence ===" > $EVIDENCE_FILE
    echo "Date: $(date)" >> $EVIDENCE_FILE
    echo "Hostname: $HOSTNAME" >> $EVIDENCE_FILE
    echo "" >> $EVIDENCE_FILE

    # Generate multiple connections
    for i in {1..5}; do
        echo -e "${YELLOW}Connection attempt $i...${NC}"
        echo "Connection attempt $i:" >> $EVIDENCE_FILE

        # Run with SSL debug to capture SNI
        KAFKA_OPTS="-Djavax.net.debug=ssl:handshake:verbose" \
            $KAFKA_HOME/bin/kafka-topics \
            --bootstrap-server $HOSTNAME:$PORT \
            --command-config $KAFKA_PLAYGROUND/admin-ssl.properties \
            --list 2>&1 | grep -E "server_name|type=host_name|CN=" | head -3 >> $EVIDENCE_FILE

        echo "" >> $EVIDENCE_FILE
        sleep 2
    done

    echo "✅ Generated 5 TLS connections"
}

# Analyze JFR recording
analyze_jfr() {
    echo -e "\n${GREEN}Step 4: Analyzing JFR Recording${NC}"

    # Dump JFR if still recording
    if [ -n "$KAFKA_PID" ] && ps -p $KAFKA_PID > /dev/null; then
        echo "Dumping JFR recording..."
        jcmd $KAFKA_PID JFR.dump name=SNITest filename=final-$JFR_OUTPUT 2>&1 | head -5

        if [ -f "final-$JFR_OUTPUT" ]; then
            JFR_OUTPUT="final-$JFR_OUTPUT"
        fi
    fi

    echo "" >> $EVIDENCE_FILE
    echo "=== JFR Recording Analysis ===" >> $EVIDENCE_FILE
    echo "JFR File: $JFR_OUTPUT" >> $EVIDENCE_FILE
    echo "Size: $(ls -lh $JFR_OUTPUT | awk '{print $5}')" >> $EVIDENCE_FILE

    # Try to find and use jfr command if available
    JFR_CMD=$(find /Library/Java/JavaVirtualMachines -name jfr 2>/dev/null | head -1)

    if [ -n "$JFR_CMD" ] && [ -f "$JFR_CMD" ]; then
        echo "Found jfr command: $JFR_CMD" >> $EVIDENCE_FILE
        echo "Analyzing JFR for TLS events..." >> $EVIDENCE_FILE

        # Check for TLS events in JFR
        TLS_COUNT=$($JFR_CMD print --events jdk.TLSHandshake $JFR_OUTPUT 2>/dev/null | grep -c "peerHost" || echo "0")
        CERT_COUNT=$($JFR_CMD print --events jdk.X509Certificate $JFR_OUTPUT 2>/dev/null | grep -c "subject" || echo "0")

        echo "TLS Handshake events: $TLS_COUNT" >> $EVIDENCE_FILE
        echo "X.509 Certificate events: $CERT_COUNT" >> $EVIDENCE_FILE
    else
        echo "jfr command not found, using jcmd for verification" >> $EVIDENCE_FILE
        jcmd $KAFKA_PID JFR.check 2>&1 | head -10 >> $EVIDENCE_FILE
    fi

    echo "✅ JFR recording saved: $JFR_OUTPUT"
}

# Verify certificates
verify_certificates() {
    echo -e "\n${GREEN}Step 5: Verifying Certificates${NC}"

    echo "" >> $EVIDENCE_FILE
    echo "=== Certificate Verification ===" >> $EVIDENCE_FILE

    # Check CN
    CN=$(openssl x509 -in $KAFKA_PLAYGROUND/ssl/broker-cert-signed -subject -noout | grep -o "CN=[^,]*" | cut -d= -f2)
    echo "Certificate CN: $CN" >> $EVIDENCE_FILE

    if [ "$CN" = "$HOSTNAME" ]; then
        echo "✅ Certificate CN matches hostname"
    else
        echo -e "${RED}❌ Certificate CN mismatch${NC}"
    fi

    # Check SANs
    echo "Certificate SANs:" >> $EVIDENCE_FILE
    openssl x509 -in $KAFKA_PLAYGROUND/ssl/broker-cert-signed -text -noout | \
        grep -A 1 "Subject Alternative Name" >> $EVIDENCE_FILE

    echo "✅ Certificate verification complete"
}

# Generate summary
generate_summary() {
    echo -e "\n${GREEN}======================================"
    echo "Test Summary & Verification"
    echo "======================================${NC}"

    echo "" >> $EVIDENCE_FILE
    echo "=== Test Summary ===" >> $EVIDENCE_FILE

    # PRIMARY VERIFICATION: Check server-side SNI reception
    SNI_CONSUMED=$(grep -c "Consumed extension: server_name" kafka-jfr.log 2>/dev/null || echo "0")

    # Secondary checks
    SNI_SENT=$(grep -c "type=host_name.*$HOSTNAME" $EVIDENCE_FILE || echo "0")

    echo "Results:" | tee -a $EVIDENCE_FILE
    echo "- JFR Recording: $JFR_OUTPUT ($(ls -lh $JFR_OUTPUT 2>/dev/null | awk '{print $5}' || echo 'N/A'))" | tee -a $EVIDENCE_FILE
    echo "- Server consumed SNI: $SNI_CONSUMED times" | tee -a $EVIDENCE_FILE
    echo "- Client sent SNI: $SNI_SENT times" | tee -a $EVIDENCE_FILE
    echo "- Certificate CN: $CN" | tee -a $EVIDENCE_FILE
    echo "- Evidence file: $EVIDENCE_FILE" | tee -a $EVIDENCE_FILE

    echo -e "\n${YELLOW}Verification Commands:${NC}"
    echo "1. Quick Pass Check:"
    echo "   grep -q 'Consumed extension: server_name' kafka-jfr.log && echo 'PASS' || echo 'FAIL'"
    echo ""
    echo "2. Analyze JFR (if jfr command available):"
    echo "   jfr print --events jdk.TLSHandshake $JFR_OUTPUT | grep peerHost"
    echo ""
    echo "3. Check with jcmd:"
    echo "   jcmd $(pgrep -f kafka.Kafka) JFR.check"

    # PASS/FAIL determination based on server-side evidence
    if [ $SNI_CONSUMED -gt 0 ] && [ "$CN" = "$HOSTNAME" ]; then
        echo -e "\n${GREEN}✅ TEST PASSED${NC}" | tee -a $EVIDENCE_FILE
        echo "Server received SNI extension $SNI_CONSUMED times" | tee -a $EVIDENCE_FILE
        echo "Certificate CN matches: $HOSTNAME" | tee -a $EVIDENCE_FILE
    else
        echo -e "\n${RED}❌ TEST FAILED${NC}" | tee -a $EVIDENCE_FILE
        if [ $SNI_CONSUMED -eq 0 ]; then
            echo "Server did not receive SNI extension" | tee -a $EVIDENCE_FILE
        fi
        if [ "$CN" != "$HOSTNAME" ]; then
            echo "Certificate CN mismatch: $CN != $HOSTNAME" | tee -a $EVIDENCE_FILE
        fi
        echo "Check $EVIDENCE_FILE and kafka-jfr.log for details" | tee -a $EVIDENCE_FILE
    fi
}

# Main execution
main() {
    echo -e "${BLUE}Starting JFR SNI/CN Test...${NC}"

    # Trap to ensure cleanup on exit
    trap cleanup EXIT

    # Run test steps
    check_prerequisites
    cleanup
    start_services
    generate_traffic
    analyze_jfr
    verify_certificates
    generate_summary

    echo -e "\n${YELLOW}To stop services manually:${NC}"
    echo "  $KAFKA_HOME/bin/kafka-server-stop"
    echo "  $KAFKA_HOME/bin/zookeeper-server-stop"

    echo -e "\n${GREEN}Test complete!${NC}"
}

# Run main function
main "$@"