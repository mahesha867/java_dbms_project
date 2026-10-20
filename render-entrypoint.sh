#!/bin/sh
set -e

# Render assigns a dynamic port via the $PORT environment variable (defaults to 10000)
TARGET_PORT="${PORT:-10000}"
echo "[Render] Configuring Tomcat to listen on HTTP port ${TARGET_PORT}..."

# Disable Tomcat TCP shutdown port (-1) so Render port-scanner does not hit port 8005
sed -i 's/<Server port="8005"/<Server port="-1"/g' /usr/local/tomcat/conf/server.xml

# Update server.xml HTTP connector to use the dynamic port
sed -i "s/port=\"8080\"/port=\"${TARGET_PORT}\"/g" /usr/local/tomcat/conf/server.xml

echo "[Render] Starting Apache Tomcat on port ${TARGET_PORT}..."
exec /usr/local/tomcat/bin/catalina.sh run


