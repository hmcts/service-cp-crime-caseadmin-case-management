# ---- Base image (default fallback) ----
ARG BASE_IMAGE
FROM ${BASE_IMAGE:-eclipse-temurin:25-jre}

# ---- Runtime arguments ----
ARG SERVER_PORT
ARG JAR_FILENAME
ARG JAR_FILE_PATH
ARG CP_BACKEND_URL
ARG CJSCPPUID

ENV JAR_FILENAME=${JAR_FILENAME:-app.jar}
ENV JAR_FILE_PATH=${JAR_FILE_PATH:-build/libs}
ENV JAR_FULL_PATH=$JAR_FILE_PATH/$JAR_FILENAME

ENV CP_BACKEND_URL=$CP_BACKEND_URL
ENV CJSCPPUID=$CJSCPPUID

# ---- Set runtime ENV for Spring Boot to bind port
ENV SERVER_PORT=${SERVER_PORT:-4550}

# ---- Dependencies ----
# The apt runtime library is named per Ubuntu release (libapt-pkg6.0t64 on 24.04 noble, which the
# publish pipeline passes as BASE_IMAGE; libapt-pkg7.0 on 26.04, the default eclipse-temurin:25-jre),
# so upgrade whichever one is installed rather than naming it.
RUN apt-get update \
    && apt-get install -y curl \
    && apt-get install -y --only-upgrade libbrotli1 apt \
       $(dpkg-query -W -f='${db:Status-Abbrev} ${Package}\n' 'libapt-pkg*' | awk '$1 == "ii" { print $2 }') \
    && rm -rf /var/lib/apt/lists/*

# ---- Application files ----
COPY $JAR_FULL_PATH /opt/app/app.jar

# ---- Permissions ----
RUN chmod 755 /opt/app/app.jar

# ---- Runtime ----
EXPOSE 4550

CMD ["java", "-jar", "/opt/app/app.jar"]