FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml pom.xml
COPY src src

RUN mvn -B -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/classes /app/classes
COPY --from=build /app/target/dependency /app/dependency

CMD ["java", "-cp", "/app/classes:/app/dependency/*", "com.example.streaming.JanusVideoViewerApp"]
