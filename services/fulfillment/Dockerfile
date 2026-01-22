FROM eclipse-temurin:17-jdk

WORKDIR /app

# Install sbt manually using the official method
RUN apt-get update && apt-get install -y \
    curl \
    ca-certificates \
    openssl \
    unzip \
    && update-ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Download and install sbt
ENV SBT_VERSION=1.9.0
RUN curl -fsL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /usr/local && \
    chmod +x /usr/local/sbt/bin/sbt

ENV PATH="/usr/local/sbt/bin:${PATH}"

# Copy build files
COPY build.sbt ./
COPY project/ ./project/

# Download dependencies
RUN sbt update

# Copy source files
COPY . .

# Build application and generate Swagger spec
RUN sbt swagger stage

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

# Update CA certificates and sync to Java truststore
RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates ca-certificates-java openssl curl && \
    update-ca-certificates && \
    /var/lib/dpkg/info/ca-certificates-java.postinst configure && \
    CACERTS_JAVA=/etc/ssl/certs/java/cacerts && \
    CACERTS_JAVA_DEFAULT=$JAVA_HOME/lib/security/cacerts && \
    if [ -f "$CACERTS_JAVA" ] && [ -f "$CACERTS_JAVA_DEFAULT" ]; then \
        cp "$CACERTS_JAVA" "$CACERTS_JAVA_DEFAULT"; \
    fi && \
    rm -rf /var/lib/apt/lists/*

# Copy stage output from build
COPY --from=0 /app/target/universal/stage ./

# Run the application
CMD ["./bin/fulfillment-service"]
