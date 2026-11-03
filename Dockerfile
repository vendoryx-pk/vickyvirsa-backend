# ---- build fat JAR ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

# ---- run ----
FROM eclipse-temurin:17-jre

RUN mkdir -p /home/user/app/tomcat-base \
 && (getent passwd 1000 >/dev/null || useradd -m -u 1000 user) \
 && chown -R 1000:1000 /home/user

USER 1000
WORKDIR /home/user/app

ENV PORT=8080

COPY --chown=1000:1000 --from=build /app/target/vendoryx-backend.jar /home/user/app/app.jar

EXPOSE 8080

CMD ["sh", "-c", "java -Xmx320m -XX:+UseSerialGC -jar /home/user/app/app.jar"]
