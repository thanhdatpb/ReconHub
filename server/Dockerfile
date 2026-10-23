# One build for both Spring Boot apps; docker compose picks the stage with `target: api` or `target: batch`.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY core/pom.xml core/
COPY api/pom.xml api/
COPY batch/pom.xml batch/
COPY core/src core/src
COPY api/src api/src
COPY batch/src batch/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
ENV TZ=Asia/Ho_Chi_Minh \
    JAVA_OPTS="-XX:MaxRAMPercentage=60"
RUN mkdir -p /data /app && chown 1000:1000 /data
WORKDIR /app
USER 1000:1000

FROM runtime AS api
COPY --from=build /src/api/target/reconhub-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

FROM runtime AS batch
COPY --from=build /src/batch/target/reconhub-batch.jar app.jar
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
