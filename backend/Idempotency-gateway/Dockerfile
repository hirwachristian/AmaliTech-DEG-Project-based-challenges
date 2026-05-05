FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy Maven build
COPY target/idempotency-gateway-1.0.0.jar app.jar

# Expose port
EXPOSE 8001

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
