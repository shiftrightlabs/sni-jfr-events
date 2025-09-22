#!/bin/bash

# Simple Kafka SNI Test with JFR
# Tests SNI without custom agent first

set -e

# Configuration
KAFKA_HOME="/Users/ansonau/kafka-playground/confluent-7.6.6"
KAFKA_PLAYGROUND="/Users/ansonau/kafka-playground"
CLIENT_HOSTNAME="kafka-with-alias.domaina.com"
PORT="9093"
JFR_OUTPUT="kafka-sni-test-$(date +%Y%m%d-%H%M%S).jfr"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}======================================"
echo "Simple Kafka SNI Test with JFR"
echo "Client Hostname: $CLIENT_HOSTNAME"
echo "======================================${NC}"

# Cleanup
cleanup() {
    echo -e "\n${YELLOW}Cleaning up...${NC}"
    pkill -f kafka.Kafka 2>/dev/null || true
    pkill -f zookeeper 2>/dev/null || true
    rm -f /tmp/kafka-logs/.lock 2>/dev/null || true
    sleep 3
}

# Start services with basic JFR
start_services() {
    echo -e "\n${GREEN}Starting Services${NC}"

    # Clean state
    rm -rf /tmp/kafka-logs /tmp/zookeeper

    # Start Zookeeper
    echo "Starting Zookeeper..."
    $KAFKA_HOME/bin/zookeeper-server-start -daemon \
        $KAFKA_HOME/etc/kafka/zookeeper.properties
    sleep 5

    if pgrep -f "zookeeper" > /dev/null; then
        echo "✅ Zookeeper started"
    else
        echo -e "${RED}❌ Failed to start Zookeeper${NC}"
        exit 1
    fi

    # Start Kafka with basic JFR (no custom events)
    echo "Starting Kafka with JFR..."
    JFR_OPTS="-XX:StartFlightRecording=name=SNITest,filename=$JFR_OUTPUT,dumponexit=true,settings=profile,+jdk.TLSHandshake#enabled=true"

    KAFKA_OPTS="$JFR_OPTS -Djavax.net.debug=ssl:handshake" \
        $KAFKA_HOME/bin/kafka-server-start \
        $KAFKA_HOME/etc/kafka/server.properties > kafka-sni-test.log 2>&1 &

    KAFKA_PID=$!
    echo "Kafka PID: $KAFKA_PID"

    sleep 15

    if ps -p $KAFKA_PID > /dev/null; then
        echo "✅ Kafka started with JFR"
    else
        echo -e "${RED}❌ Failed to start Kafka${NC}"
        tail -20 kafka-sni-test.log
        exit 1
    fi
}

# Test connections
test_connections() {
    echo -e "\n${GREEN}Testing Connections${NC}"

    # Create output file for SNI evidence
    SNI_LOG="sni-evidence-$(date +%Y%m%d-%H%M%S).log"

    echo "=== SNI Test Evidence ===" > $SNI_LOG
    echo "Date: $(date)" >> $SNI_LOG
    echo "Client Hostname: $CLIENT_HOSTNAME" >> $SNI_LOG
    echo "" >> $SNI_LOG

    # Test connection with kafka-with-alias.domaina.com
    echo -e "${YELLOW}Testing with $CLIENT_HOSTNAME...${NC}"

    for i in {1..5}; do
        echo "Connection attempt $i:" >> $SNI_LOG

        # Run with SSL debug to capture SNI
        KAFKA_OPTS="-Djavax.net.debug=ssl:handshake:verbose" \
            $KAFKA_HOME/bin/kafka-topics \
            --bootstrap-server $CLIENT_HOSTNAME:$PORT \
            --command-config $KAFKA_PLAYGROUND/admin-ssl.properties \
            --list 2>&1 | grep -E "server_name|type=host_name|SNI|ClientHello" | head -5 >> $SNI_LOG

        echo "" >> $SNI_LOG
        sleep 2
    done

    echo "✅ Test connections completed"
    echo "SNI evidence saved to: $SNI_LOG"

    # Show SNI sent
    echo -e "\n${BLUE}SNI Sent by Client:${NC}"
    grep "type=host_name" $SNI_LOG | head -3
}

# Check server log for SNI
check_server_sni() {
    echo -e "\n${GREEN}Checking Server-side SNI Reception${NC}"

    # Check if server received SNI
    echo -e "\n${BLUE}Server-side SNI reception:${NC}"
    if grep -q "Consumed extension: server_name" kafka-sni-test.log; then
        echo "✅ Server consumed SNI extension"
        grep "Consumed extension: server_name" kafka-sni-test.log | head -3
    else
        echo "❌ No SNI consumption found in server log"
    fi

    # Check what hostname server sees
    echo -e "\n${BLUE}Hostnames in server log:${NC}"
    grep -E "server_name|SNI|ClientHello.*host" kafka-sni-test.log 2>/dev/null | head -5 || echo "No specific SNI entries found"
}

# Analyze JFR
analyze_jfr() {
    echo -e "\n${GREEN}Analyzing JFR Recording${NC}"

    # Dump JFR
    if [ -n "$KAFKA_PID" ] && ps -p $KAFKA_PID > /dev/null; then
        jcmd $KAFKA_PID JFR.dump name=SNITest filename=final-$JFR_OUTPUT
        if [ -f "final-$JFR_OUTPUT" ]; then
            JFR_OUTPUT="final-$JFR_OUTPUT"
        fi
    fi

    JFR_CMD="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/jfr"

    echo -e "\n${BLUE}TLS Handshake Events in JFR:${NC}"
    $JFR_CMD print --events jdk.TLSHandshake $JFR_OUTPUT | grep -E "peerHost" | sort | uniq -c

    echo -e "\n${GREEN}JFR file: $JFR_OUTPUT${NC}"
}

# Main
main() {
    trap cleanup EXIT

    cleanup
    start_services
    test_connections
    check_server_sni
    analyze_jfr

    echo -e "\n${GREEN}Test Complete!${NC}"
    echo ""
    echo "Summary:"
    echo "1. Client SNI evidence: Check sni-evidence-*.log"
    echo "2. Server reception: Check kafka-sni-test.log"
    echo "3. JFR recording: $JFR_OUTPUT"
}

main "$@"