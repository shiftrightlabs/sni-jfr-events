#!/bin/bash

# Kafka with Custom SNI JFR Events
# This script runs Kafka with a Java agent that captures SNI information in JFR

set -e

# Configuration
KAFKA_HOME="/Users/ansonau/kafka-playground/confluent-7.6.6"
KAFKA_PLAYGROUND="/Users/ansonau/kafka-playground"
SNI_AGENT_JAR="$KAFKA_PLAYGROUND/sni-jfr-events/target/sni-jfr-agent-1.0.0.jar"
JFC_FILE="$KAFKA_PLAYGROUND/sni-enhanced.jfc"
JFR_OUTPUT="kafka-sni-jfr-$(date +%Y%m%d-%H%M%S).jfr"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}======================================"
echo "Kafka with Custom SNI JFR Events"
echo "======================================${NC}"

# Build the agent if needed
build_agent() {
    echo -e "\n${GREEN}Building SNI JFR Agent${NC}"
    cd $KAFKA_PLAYGROUND/sni-jfr-events

    if [ ! -f pom.xml ]; then
        echo -e "${RED}Error: pom.xml not found${NC}"
        exit 1
    fi

    mvn clean package

    if [ -f target/sni-jfr-agent-1.0.0.jar ]; then
        echo "✅ Agent built successfully"
    else
        echo -e "${RED}❌ Failed to build agent${NC}"
        exit 1
    fi

    cd $KAFKA_PLAYGROUND
}

# Create enhanced JFC configuration
create_jfc_config() {
    echo -e "\n${GREEN}Creating enhanced JFC configuration${NC}"

    cat > "$JFC_FILE" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="SNI Enhanced TLS Monitoring">

  <!-- Custom SNI Event -->
  <event name="kafka.sni.Handshake">
    <setting name="enabled">true</setting>
    <setting name="threshold">0 ms</setting>
    <setting name="stackTrace">false</setting>
  </event>

  <!-- Standard TLS Events -->
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

  <!-- Network Events -->
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

    echo "✅ Enhanced JFC configuration created"
}

# Clean up previous processes
cleanup() {
    echo -e "\n${YELLOW}Cleaning up...${NC}"
    pkill -f kafka.Kafka 2>/dev/null || true
    pkill -f zookeeper 2>/dev/null || true
    rm -f /tmp/kafka-logs/.lock 2>/dev/null || true
    sleep 3
}

# Start services with agent
start_services() {
    echo -e "\n${GREEN}Starting Services with SNI Agent${NC}"

    # Clean state
    rm -rf /tmp/kafka-logs /tmp/zookeeper

    # Start Zookeeper
    echo "Starting Zookeeper..."
    $KAFKA_HOME/bin/zookeeper-server-start -daemon \
        $KAFKA_HOME/etc/kafka/zookeeper.properties
    sleep 5

    # Prepare JVM options with agent and JFR
    AGENT_OPTS="-javaagent:$SNI_AGENT_JAR"
    JFR_OPTS="-XX:StartFlightRecording=name=SNICapture,settings=$JFC_FILE,filename=$JFR_OUTPUT,maxsize=200m,dumponexit=true"

    # Start Kafka with agent and JFR
    echo "Starting Kafka with SNI JFR Agent..."
    KAFKA_OPTS="$AGENT_OPTS $JFR_OPTS" \
        $KAFKA_HOME/bin/kafka-server-start \
        $KAFKA_HOME/etc/kafka/server.properties > kafka-sni-agent.log 2>&1 &

    KAFKA_PID=$!
    echo "Kafka PID: $KAFKA_PID"

    sleep 15

    if ps -p $KAFKA_PID > /dev/null; then
        echo "✅ Kafka started with SNI JFR Agent"

        # Check if agent loaded
        if grep -q "SNI-JFR-Agent" kafka-sni-agent.log; then
            echo "✅ SNI JFR Agent loaded successfully"
        else
            echo -e "${YELLOW}⚠️  SNI JFR Agent may not have loaded${NC}"
        fi
    else
        echo -e "${RED}❌ Failed to start Kafka${NC}"
        tail -20 kafka-sni-agent.log
        exit 1
    fi
}

# Generate test traffic
test_connections() {
    echo -e "\n${GREEN}Testing Connections${NC}"

    # Test with different hostnames
    for hostname in "kafka-broker.domaina.com" "kafka-with-alias.domaina.com" "localhost"; do
        echo -e "${YELLOW}Testing with hostname: $hostname${NC}"

        $KAFKA_HOME/bin/kafka-topics \
            --bootstrap-server $hostname:9093 \
            --command-config $KAFKA_PLAYGROUND/admin-ssl.properties \
            --list 2>/dev/null || true

        sleep 2
    done

    echo "✅ Test connections completed"
}

# Analyze JFR with custom events
analyze_jfr() {
    echo -e "\n${GREEN}Analyzing JFR Recording${NC}"

    if [ -n "$KAFKA_PID" ] && ps -p $KAFKA_PID > /dev/null; then
        jcmd $KAFKA_PID JFR.dump name=SNICapture filename=final-$JFR_OUTPUT

        if [ -f "final-$JFR_OUTPUT" ]; then
            JFR_OUTPUT="final-$JFR_OUTPUT"
        fi
    fi

    JFR_CMD="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/jfr"

    echo -e "\n${BLUE}Custom SNI Events:${NC}"
    $JFR_CMD print --events "kafka.sni.*" $JFR_OUTPUT 2>/dev/null | head -50 || \
        echo "No custom SNI events found (this is expected if the agent didn't load)"

    echo -e "\n${BLUE}Standard TLS Events:${NC}"
    $JFR_CMD print --events jdk.TLSHandshake $JFR_OUTPUT | \
        grep -E "peerHost|protocolVersion" | head -20

    echo -e "\n${GREEN}JFR file saved: $JFR_OUTPUT${NC}"
}

# Main execution
main() {
    trap cleanup EXIT

    # Check if agent JAR exists, build if not
    if [ ! -f "$SNI_AGENT_JAR" ]; then
        build_agent
    else
        echo "✅ Using existing agent: $SNI_AGENT_JAR"
    fi

    create_jfc_config
    cleanup
    start_services
    test_connections
    analyze_jfr

    echo -e "\n${GREEN}Complete!${NC}"
    echo "To analyze custom SNI events:"
    echo "  $JFR_CMD print --events 'kafka.sni.*' $JFR_OUTPUT"
    echo ""
    echo "To stop services:"
    echo "  $KAFKA_HOME/bin/kafka-server-stop"
    echo "  $KAFKA_HOME/bin/zookeeper-server-stop"
}

main "$@"