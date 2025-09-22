#!/bin/bash

# Build and Deploy SNI JFR Components
# This script properly separates event classes from agent

set -e

KAFKA_HOME="/Users/ansonau/kafka-playground/confluent-7.6.6"
PROJECT_DIR="/Users/ansonau/kafka-playground/sni-jfr-events"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}Building SNI JFR Components${NC}"

# Step 1: Build the project
cd $PROJECT_DIR
mvn clean compile

# Step 2: Create event-only JAR (for classpath)
echo -e "\n${GREEN}Creating event classes JAR...${NC}"
jar cf target/sni-events-only.jar \
    -C target/classes com/kafka/jfr/sni/SNIHandshakeEvent.class

# Step 3: Create agent JAR (for instrumentation)
echo -e "\n${GREEN}Creating agent JAR...${NC}"
jar cfm target/sni-agent-only.jar \
    <(echo "Premain-Class: com.kafka.jfr.sni.SNIAgent") \
    -C target/classes com/kafka/jfr/sni/SNIAgent.class \
    -C target/classes com/kafka/jfr/sni/SNIEventEmitter.class

# Step 4: Copy event JAR to Kafka lib
echo -e "\n${GREEN}Deploying event classes to Kafka...${NC}"
cp target/sni-events-only.jar $KAFKA_HOME/share/java/kafka/

echo -e "\n${BLUE}Deployment complete!${NC}"
echo ""
echo "To run Kafka with SNI JFR monitoring:"
echo ""
echo "1. Add event classes to classpath:"
echo "   export CLASSPATH=\$KAFKA_HOME/share/java/kafka/sni-events-only.jar:\$CLASSPATH"
echo ""
echo "2. Start Kafka with agent:"
echo "   KAFKA_OPTS=\"-javaagent:$PROJECT_DIR/target/sni-agent-only.jar\" \\"
echo "   \$KAFKA_HOME/bin/kafka-server-start \\"
echo "   \$KAFKA_HOME/etc/kafka/server.properties"
echo ""
echo "3. Start JFR recording (after Kafka starts):"
echo "   jcmd <PID> JFR.start name=SNI settings=profile"
echo ""
echo "4. Configure custom events (after recording starts):"
echo "   jcmd <PID> JFR.configure +kafka.sni.Handshake#enabled=true"