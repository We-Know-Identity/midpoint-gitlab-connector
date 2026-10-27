# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
FROM maven:3.9-eclipse-temurin-21@sha256:2b4496088e7b80ae10a8c9f74e574ea21380325a006ec684532ad6bad5bc7273 AS build

WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline
COPY LICENSE NOTICE install-connector.sh ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean verify

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
COPY --from=build \
    /build/target/connector-gitlab-rest-2.3.0-easyiga-connector.jar \
    /opt/easyiga/connector/connector-gitlab-rest-2.3.0-easyiga-connector.jar
COPY install-connector.sh /usr/local/bin/install-connector
RUN chmod 0755 /usr/local/bin/install-connector

ENTRYPOINT ["/usr/local/bin/install-connector"]
