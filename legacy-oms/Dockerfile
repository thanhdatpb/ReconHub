# Build the WAR, then run it on Tomcat 9 (javax.servlet), the way the shop's old server does.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package

FROM tomcat:9.0-jdk21-temurin
ENV TZ=Asia/Ho_Chi_Minh
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/target/tomcat-lib/postgresql.jar /usr/local/tomcat/lib/
COPY --from=build /src/target/oms.war /usr/local/tomcat/webapps/oms.war
EXPOSE 8080
