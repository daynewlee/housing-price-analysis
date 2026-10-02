# Base image
FROM eclipse-temurin:17-jre

WORKDIR /app

# Handle jars
COPY target/*.jar app.jar

# SpringBoot default port
EXPOSE 8080

# start container
ENTRYPOINT ["java", "-jar", "app.jar"]
