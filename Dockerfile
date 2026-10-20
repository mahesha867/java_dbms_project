# Dockerfile for Render Web Service Deployment
# Runtime: Apache Tomcat 10.1 (Jakarta Servlet 6.0) on Eclipse Temurin OpenJDK 21

FROM tomcat:10.1-jdk21-temurin

WORKDIR /app

# Copy dependency libraries and application source code
COPY lib/ /app/lib/
COPY src/ /app/src/

# Set up webapp directory structure and sync libraries
RUN mkdir -p /app/src/main/webapp/WEB-INF/classes && \
    mkdir -p /app/src/main/webapp/WEB-INF/lib && \
    cp /app/lib/*.jar /app/src/main/webapp/WEB-INF/lib/

# Compile Java source files
RUN find /app/src/main/java -name *.java > /app/sources.txt && \
    javac -encoding UTF-8 -cp /app/lib/*:/usr/local/tomcat/lib/* -d /app/src/main/webapp/WEB-INF/classes @/app/sources.txt && \
    rm /app/sources.txt

# Remove default Tomcat web applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Deploy to both ROOT (for https://domain/login) and attendance (for https://domain/attendance/login)
RUN cd /app/src/main/webapp && \
    jar -cf /usr/local/tomcat/webapps/ROOT.war * && \
    cp /usr/local/tomcat/webapps/ROOT.war /usr/local/tomcat/webapps/attendance.war

# Clean build directory
RUN rm -rf /app

# Copy Render dynamic port entrypoint script
COPY render-entrypoint.sh /usr/local/tomcat/bin/render-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/tomcat/bin/render-entrypoint.sh && \
    chmod +x /usr/local/tomcat/bin/render-entrypoint.sh

# Expose HTTP port for Render
EXPOSE 10000

ENTRYPOINT ["/bin/sh", "/usr/local/tomcat/bin/render-entrypoint.sh"]
