#!/bin/bash

# Kafka with Custom SNI JFR Agent (Fixed)
# This version starts JFR after the agent loads

set -e

# Configuration
KAFKA_HOME="/Users/ansonau/kafka-playground/confluent-7.6.6"
KAFKA_PLAYGROUND="/Users/ansonau/kafka-playground"
SNI_AGENT_JAR="$KAFKA_PLAYGROUND/sni-jfr-events/target/sni-jfr-agent-1.0.0.jar"
CLIENT_HOSTNAME="kafka-with-alias.domaina.com"
PORT="9093"
JFR_OUTPUT="kafka-custom-sni-$(date +%Y%m%d-%H%M%S).jfr"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}======================================"
echo "Kafka with Custom SNI JFR Agent"
echo "Client Hostname: $CLIENT_HOSTNAME"
echo "======================================${NC}"

# Check agent exists
if [ ! -f "$SNI_AGENT_JAR" ]; then
    echo -e "${RED}Agent JAR not found: $SNI_AGENT_JAR${NC}"
    exit 1
fi

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
    echo -e "\n${GREEN}Starting Services with Custom Agent${NC}"

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

    # Start Kafka with agent but WITHOUT JFR initially
    # We'll start JFR recording after the JVM starts
    echo "Starting Kafka with SNI agent..."

    AGENT_OPTS="-javaagent:$SNI_AGENT_JAR"
    # Add basic JFR but don't reference custom events yet
    JFR_OPTS="-XX:FlightRecorderOptions=stackdepth=128"

    KAFKA_OPTS="$AGENT_OPTS $JFR_OPTS -Djavax.net.debug=ssl:handshake" \
        $KAFKA_HOME/bin/kafka-server-start \
        $KAFKA_HOME/etc/kafka/server.properties > kafka-custom-agent.log 2>&1 &

    KAFKA_PID=$!
    echo "Kafka PID: $KAFKA_PID"

    sleep 15

    if ps -p $KAFKA_PID > /dev/null; then
        echo "✅ Kafka started with agent"

        # Check if agent loaded
        if grep -q "SNI-JFR-Agent" kafka-custom-agent.log; then
            echo "✅ SNI JFR Agent loaded"
            grep "SNI-JFR-Agent" kafka-custom-agent.log | head -3
        else
            echo -e "${YELLOW}⚠️  Agent may not have loaded properly${NC}"
        fi

        # Now start JFR recording AFTER the agent is loaded
        echo "Starting JFR recording..."
        jcmd $KAFKA_PID JFR.start name=CustomSNI filename=$JFR_OUTPUT settings=profile

    else
        echo -e "${RED}❌ Failed to start Kafka${NC}"
        tail -20 kafka-custom-agent.log
        exit 1
    fi
}

# Test connections
test_connections() {
    echo -e "\n${GREEN}Testing Connections${NC}"

    SNI_LOG="custom-sni-evidence-$(date +%Y%m%d-%H%M%S).log"

    echo "=== Custom SNI Test Evidence ===" > $SNI_LOG
    echo "Date: $(date)" >> $SNI_LOG
    echo "Client Hostname: $CLIENT_HOSTNAME" >> $SNI_LOG
    echo "" >> $SNI_LOG

    # Test multiple times
    for i in {1..5}; do
        echo -e "${YELLOW}Connection $i with $CLIENT_HOSTNAME...${NC}"

        echo "Connection attempt $i:" >> $SNI_LOG

        # Run client with debug
        KAFKA_OPTS="-Djavax.net.debug=ssl:handshake:verbose" \
            $KAFKA_HOME/bin/kafka-topics \
            --bootstrap-server $CLIENT_HOSTNAME:$PORT \
            --command-config $KAFKA_PLAYGROUND/admin-ssl.properties \
            --list 2>&1 | grep -E "server_name|type=host_name|SNI" | head -5 >> $SNI_LOG

        echo "" >> $SNI_LOG
        sleep 2
    done

    echo "✅ Test connections completed"

    # Check for custom event emissions
    echo -e "\n${BLUE}Checking for custom SNI event emissions:${NC}"
    if grep -q "SNI-JFR.*Recorded" kafka-custom-agent.log; then
        echo "✅ Custom SNI events were emitted:"
        grep "SNI-JFR.*Recorded" kafka-custom-agent.log | head -5
    else
        echo "❌ No custom SNI events found in log"
    fi
}

# Analyze JFR
analyze_jfr() {
    echo -e "\n${GREEN}Analyzing JFR Recording${NC}"

    # Stop and dump JFR
    if [ -n "$KAFKA_PID" ] && ps -p $KAFKA_PID > /dev/null; then
        echo "Stopping JFR recording..."
        jcmd $KAFKA_PID JFR.stop name=CustomSNI

        # Also check if there's a dump file
        if [ -f "$JFR_OUTPUT" ]; then
            echo "✅ JFR file exists: $JFR_OUTPUT"
        else
            echo "❌ JFR file not found"
        fi
    fi

    JFR_CMD="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/jfr"

    if [ -f "$JFR_OUTPUT" ]; then
        echo -e "\n${BLUE}Searching for custom SNI events:${NC}"
        # Try to find custom events
        $JFR_CMD print $JFR_OUTPUT | grep -i "sni\|kafka.sni" | head -10 || echo "No custom SNI events found in JFR"

        echo -e "\n${BLUE}Standard TLS events:${NC}"
        $JFR_CMD print --events jdk.TLSHandshake $JFR_OUTPUT | grep peerHost | sort | uniq -c | head -5
    fi
}

# Check server log
check_server_log() {
    echo -e "\n${GREEN}Server-side SNI Reception${NC}"

    if grep -q "Consumed extension: server_name" kafka-custom-agent.log; then
        echo "✅ Server consumed SNI extension"
        grep "Consumed extension: server_name" kafka-custom-agent.log | head -3
    else
        echo "❌ No SNI consumption found"
    fi
}

# Main
main() {
    trap cleanup EXIT

    cleanup
    start_services
    test_connections
    check_server_log
    analyze_jfr

    echo -e "\n${GREEN}Test Complete!${NC}"
    echo ""
    echo "Check these files:"
    echo "1. kafka-custom-agent.log - Server log with agent output"
    echo "2. custom-sni-evidence-*.log - Client SNI evidence"
    echo "3. $JFR_OUTPUT - JFR recording"
}

main "$@"