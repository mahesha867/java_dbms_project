#!/bin/sh
set -e

# Render assigns a dynamic port via the  environment variable (defaults to 10000 or 8080)
TARGET_PORT="
echo [Render] Configuring Tomcat to listen on HTTP port ...

# Update server.xml to use the dynamic port
sed -i s/port="8080"/port=""/g /usr/local/tomcat/conf/server.xml

echo [Render] Starting Apache Tomcat on port ...
exec /usr/local/tomcat/bin/catalina.sh run
