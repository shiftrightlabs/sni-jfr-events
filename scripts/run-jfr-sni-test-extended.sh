#!/bin/bash

# Extended Kafka JFR SNI Test Script
# Ensures proper capture of client TLS handshake events with hostname

set -e

# Configuration
KAFKA_HOME="/Users/ansonau/kafka-playground/confluent-7.6.6"
KAFKA_PLAYGROUND="/Users/ansonau/kafka-playground"
CLIENT_HOSTNAME="kafka-with-alias.domaina.com"
PORT="9093"
JFC_FILE="$KAFKA_PLAYGROUND/custom-tls.jfc"
JFR_OUTPUT="kafka-jfr-extended-$(date +%Y%m%d-%H%M%S).jfr"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}======================================"
echo "Extended Kafka JFR SNI Test"
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

# Start services
start_services() {
    echo -e "\n${GREEN}Starting Services${NC}"

    # Clean state
    rm -rf /tmp/kafka-logs /tmp/zookeeper

    # Start Zookeeper
    echo "Starting Zookeeper..."
    $KAFKA_HOME/bin/zookeeper-server-start -daemon $KAFKA_HOME/etc/kafka/zookeeper.properties
    sleep 5

    # Start Kafka with JFR
    echo "Starting Kafka with JFR recording..."
    JFR_OPTS="-XX:StartFlightRecording=name=SNITest,settings=$JFC_FILE,filename=$JFR_OUTPUT,maxsize=200m,dumponexit=true"
    KAFKA_OPTS="$JFR_OPTS" $KAFKA_HOME/bin/kafka-server-start \
        $KAFKA_HOME/etc/kafka/server.properties > kafka-extended.log 2>&1 &
    KAFKA_PID=$!

    echo "Kafka PID: $KAFKA_PID"
    sleep 15  # Give more time for Kafka to fully start

    if ps -p $KAFKA_PID > /dev/null; then
        echo "✅ Kafka started with JFR recording"
    else
        echo -e "${RED}❌ Failed to start Kafka${NC}"
        tail -20 kafka-extended.log
        exit 1
    fi
}

# Generate traffic and wait for client connections
generate_traffic() {
    echo -e "\n${GREEN}Generating Client Traffic${NC}"

    # Create a topic first
    $KAFKA_HOME/bin/kafka-topics \
        --bootstrap-server $CLIENT_HOSTNAME:$PORT \
        --command-config $KAFKA_PLAYGROUND/admin-ssl.properties \
        --create --topic test-sni-topic --if-not-exists 2>/dev/null || true

    # Multiple client connections with delays to ensure capture
    for i in {1..10}; do
        echo -e "${YELLOW}Client connection $i to $CLIENT_HOSTNAME...${NC}"

        # List topics (creates SSL connection)
        $KAFKA_HOME/bin/kafka-topics \
            --bootstrap-server $CLIENT_HOSTNAME:$PORT \
            --command-config $KAFKA_PLAYGROUND/admin-ssl.properties \
            --list > /dev/null 2>&1

        sleep 2

        # Produce a message (another SSL connection)
        echo "test-message-$i" | $KAFKA_HOME/bin/kafka-console-producer \
            --bootstrap-server $CLIENT_HOSTNAME:$PORT \
            --topic test-sni-topic \
            --producer.config $KAFKA_PLAYGROUND/admin-ssl.properties > /dev/null 2>&1

        sleep 2
    done

    echo "✅ Generated 10 client connections"

    # Wait for events to be written to JFR
    echo "Waiting for JFR to capture events..."
    sleep 10
}

# Analyze JFR
analyze_jfr() {
    echo -e "\n${GREEN}Analyzing JFR Recording${NC}"

    # Dump current recording
    if [ -n "$KAFKA_PID" ] && ps -p $KAFKA_PID > /dev/null; then
        echo "Dumping JFR recording..."
        jcmd $KAFKA_PID JFR.dump name=SNITest filename=final-$JFR_OUTPUT

        if [ -f "final-$JFR_OUTPUT" ]; then
            JFR_OUTPUT="final-$JFR_OUTPUT"
        fi
    fi

    # Analyze with jfr tool
    JFR_CMD="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/jfr"

    echo -e "\n${BLUE}JFR Analysis:${NC}"
    echo "File: $JFR_OUTPUT ($(ls -lh $JFR_OUTPUT | awk '{print $5}'))"

    # Check for TLS events with hostnames
    echo -e "\n${YELLOW}Checking for TLS Handshake events with hostnames:${NC}"
    HOSTS_FOUND=$($JFR_CMD print --events jdk.TLSHandshake $JFR_OUTPUT | grep -E "peerHost = \"[^\"]+\"" | grep -v "peerHost = \"\"" | head -10)

    if [ -n "$HOSTS_FOUND" ]; then
        echo "$HOSTS_FOUND"

        # Check specifically for our client hostname
        if echo "$HOSTS_FOUND" | grep -q "$CLIENT_HOSTNAME"; then
            echo -e "\n${GREEN}✅ SUCCESS: Found $CLIENT_HOSTNAME in JFR TLS events${NC}"
        else
            echo -e "\n${YELLOW}⚠️  $CLIENT_HOSTNAME not found in JFR, but other hosts captured${NC}"
        fi
    else
        echo -e "${RED}❌ No hostnames captured in TLS handshake events${NC}"

        # Check event count
        TLS_COUNT=$($JFR_CMD print --events jdk.TLSHandshake $JFR_OUTPUT | grep -c "jdk.TLSHandshake" || echo "0")
        echo "Total TLS Handshake events: $TLS_COUNT"
    fi

    # Summary
    echo -e "\n${BLUE}Summary:${NC}"
    $JFR_CMD summary $JFR_OUTPUT | grep -E "TLSHandshake|X509"
}

# Main
main() {
    trap cleanup EXIT

    cleanup
    start_services
    generate_traffic
    analyze_jfr

    echo -e "\n${GREEN}Test complete!${NC}"
    echo "JFR file: $JFR_OUTPUT"
    echo ""
    echo "To manually check for hostnames in JFR:"
    echo "  /Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/jfr print --events jdk.TLSHandshake $JFR_OUTPUT | grep peerHost"
}

main "$@"