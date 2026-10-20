#!/bin/sh
set -e

# Render assigns a dynamic port via the $PORT environment variable (defaults to 10000 or 8080)
TARGET_PORT="${PORT:-8080}"
echo "[Render] Configuring Tomcat to listen on HTTP port ${TARGET_PORT}..."

# Update server.xml to use the dynamic port
sed -i "s/port=\"8080\"/port=\"${TARGET_PORT}\"/g" /usr/local/tomcat/conf/server.xml

echo "[Render] Starting Apache Tomcat on port ${TARGET_PORT}..."
exec /usr/local/tomcat/bin/catalina.sh run

