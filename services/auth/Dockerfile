FROM eclipse-temurin:17-jdk

WORKDIR /app

# Install sbt manually using the official method
RUN apt-get update && apt-get install -y \
    curl \
    ca-certificates \
    unzip \
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

# Build application
RUN sbt stage

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy stage output from build
COPY --from=0 /app/target/universal/stage ./

# Run the application
CMD ["./bin/auth-service"]
