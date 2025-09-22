#!/bin/bash

# Script to generate certificates and keystores for Kafka mTLS authentication
# with Subject Alternative Names (SANs) for multiple hostnames
# Following Confluent 7.6 documentation

set -e

# Configuration
VALIDITY=365
KEYSTORE_PASSWORD=kafka123
KEY_PASSWORD=kafka123
TRUSTSTORE_PASSWORD=kafka123
CA_CERT_ALIAS="ca-cert"
BROKER_ALIAS="kafka-broker"
CLIENT_ALIAS="kafka-client"

# Directory setup
CERT_DIR="/Users/ansonau/kafka-playground/ssl"
rm -rf "$CERT_DIR"
mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

echo "======================================"
echo "Generating CA Certificate and Key"
echo "======================================"

# Generate CA key pair
openssl req -new -x509 -keyout ca-key -out ca-cert -days $VALIDITY \
  -subj "/C=US/ST=CA/L=PaloAlto/O=MyOrg/CN=CARoot" \
  -passout pass:$KEYSTORE_PASSWORD

echo "CA certificate generated"

echo "======================================"
echo "Creating Broker Keystore with SANs"
echo "======================================"

# Create SAN configuration file for broker certificate
cat > broker-san.cnf <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
C = US
ST = CA
L = PaloAlto
O = MyOrg
OU = Engineering
CN = kafka-broker.domaina.com

[v3_req]
subjectAltName = @alt_names

[alt_names]
DNS.1 = kafka-broker.domaina.com
DNS.2 = kafka-broker
DNS.3 = kafka-with-alias.domaina.com
DNS.4 = kafka-with-alias
DNS.5 = localhost
IP.1 = 127.0.0.1
EOF

# Generate broker private key
openssl genrsa -out broker-key 2048

# Generate broker certificate request with SANs
openssl req -new -key broker-key -out broker-cert-request \
  -config broker-san.cnf

# Sign broker certificate with CA, including SANs
openssl x509 -req -CA ca-cert -CAkey ca-key \
  -in broker-cert-request -out broker-cert-signed \
  -days $VALIDITY -CAcreateserial \
  -extensions v3_req -extfile broker-san.cnf \
  -passin pass:$KEYSTORE_PASSWORD

# Create PKCS12 keystore with broker certificate
openssl pkcs12 -export \
  -in broker-cert-signed \
  -inkey broker-key \
  -certfile ca-cert \
  -name $BROKER_ALIAS \
  -out broker.p12 \
  -passout pass:$KEYSTORE_PASSWORD

# Convert PKCS12 to JKS
keytool -importkeystore \
  -srckeystore broker.p12 \
  -srcstoretype pkcs12 \
  -srcstorepass $KEYSTORE_PASSWORD \
  -destkeystore kafka.broker.keystore.jks \
  -deststoretype jks \
  -deststorepass $KEYSTORE_PASSWORD \
  -destkeypass $KEY_PASSWORD \
  -noprompt

echo "Broker keystore created with SANs"

echo "======================================"
echo "Creating Client Keystore and Certificate"
echo "======================================"

# Create client keystore
keytool -genkey -noprompt \
  -alias $CLIENT_ALIAS \
  -dname "CN=kafka-client,OU=Engineering,O=MyOrg,L=PaloAlto,ST=CA,C=US" \
  -keystore kafka.client.keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity $VALIDITY \
  -storepass $KEYSTORE_PASSWORD \
  -keypass $KEY_PASSWORD

# Create client certificate signing request
keytool -certreq \
  -alias $CLIENT_ALIAS \
  -file client-cert-request \
  -keystore kafka.client.keystore.jks \
  -storepass $KEYSTORE_PASSWORD \
  -keypass $KEY_PASSWORD

# Sign client certificate with CA
openssl x509 -req -CA ca-cert -CAkey ca-key \
  -in client-cert-request -out client-cert-signed \
  -days $VALIDITY -CAcreateserial \
  -passin pass:$KEYSTORE_PASSWORD

# Import CA certificate into client keystore
keytool -import -noprompt \
  -alias $CA_CERT_ALIAS \
  -file ca-cert \
  -keystore kafka.client.keystore.jks \
  -storepass $KEYSTORE_PASSWORD

# Import signed client certificate into client keystore
keytool -import -noprompt \
  -alias $CLIENT_ALIAS \
  -file client-cert-signed \
  -keystore kafka.client.keystore.jks \
  -storepass $KEYSTORE_PASSWORD

echo "Client keystore created"

echo "======================================"
echo "Creating Truststores"
echo "======================================"

# Create broker truststore with CA certificate
keytool -import -noprompt \
  -alias $CA_CERT_ALIAS \
  -file ca-cert \
  -keystore kafka.broker.truststore.jks \
  -storepass $TRUSTSTORE_PASSWORD

# Create client truststore with CA certificate
keytool -import -noprompt \
  -alias $CA_CERT_ALIAS \
  -file ca-cert \
  -keystore kafka.client.truststore.jks \
  -storepass $TRUSTSTORE_PASSWORD

echo "Truststores created"

echo "======================================"
echo "Verifying SANs in Broker Certificate"
echo "======================================"

echo "Certificate details:"
openssl x509 -in broker-cert-signed -text -noout | grep -A 1 "Subject Alternative Name"

echo ""
echo "======================================"
echo "Certificate Generation Complete!"
echo "======================================"
echo ""
echo "Generated files in $CERT_DIR:"
echo "  - kafka.broker.keystore.jks   (Broker keystore with SANs)"
echo "  - kafka.broker.truststore.jks (Broker truststore)"
echo "  - kafka.client.keystore.jks   (Client keystore)"
echo "  - kafka.client.truststore.jks (Client truststore)"
echo "  - ca-cert                      (CA certificate)"
echo ""
echo "SANs included in broker certificate:"
echo "  - kafka-broker.domaina.com"
echo "  - kafka-broker"
echo "  - kafka-with-alias"
echo "  - localhost"
echo "  - 127.0.0.1"
echo ""
echo "Passwords:"
echo "  - Keystore password: $KEYSTORE_PASSWORD"
echo "  - Key password: $KEY_PASSWORD"
echo "  - Truststore password: $TRUSTSTORE_PASSWORD"