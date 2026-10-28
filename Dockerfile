# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src
# Single package step (skip flaky dependency:go-offline). Artifacts:
#   target/classes, target/dependency/*.jar, src/main/webapp (WEB-INF/web.xml)
RUN mvn -B -DskipTests package dependency:copy-dependencies \
    -DexcludeScope=provided \
    -DoutputDirectory=target/dependency

# ---- run (Render / Hugging Face Spaces: non-root uid 1000) ----
FROM eclipse-temurin:17-jre

# Temurin Ubuntu images often already have uid 1000 ("ubuntu"). Creating
# another user with -u 1000 fails the build — reuse uid/gid 1000 instead.
RUN mkdir -p /home/user/app/tomcat-base \
 && (getent passwd 1000 >/dev/null || useradd -m -u 1000 user) \
 && chown -R 1000:1000 /home/user

USER 1000
WORKDIR /home/user/app

ENV PORT=8080
ENV VENDORYX_WEBAPP_DIR=/home/user/app/webapp
ENV VENDORYX_CLASSES_DIR=/home/user/app/classes

COPY --chown=1000:1000 --from=build /app/target/classes /home/user/app/classes
COPY --chown=1000:1000 --from=build /app/target/dependency /home/user/app/dependency
COPY --chown=1000:1000 --from=build /app/src/main/webapp /home/user/app/webapp

EXPOSE 8080

CMD ["sh", "-c", "java -Xmx320m -XX:+UseSerialGC -cp '/home/user/app/classes:/home/user/app/dependency/*' com.vendoryx.AppServer"]
