ARG DOCKER_REGISTRY=
FROM ${DOCKER_REGISTRY}maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Xmx768m -XX:MaxRAMPercentage=75 -Djava.awt.headless=true"
COPY .mvn ./.mvn
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -e package -DskipTests

FROM ${DOCKER_REGISTRY}eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS=""
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/biecuoguo-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
