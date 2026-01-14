# Build stage
FROM python:3.11-slim as builder

WORKDIR /app

# Install uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /bin/uv

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Copy project files for dependency resolution
COPY pyproject.toml ./

# Copy app directory structure (minimal for uv sync to work)
COPY app/ ./app/

# Install Python dependencies using uv sync
# uv sync creates a virtual environment and installs dependencies
RUN uv sync --no-dev

# Runtime stage
FROM python:3.11-slim

WORKDIR /app

# Install uv for runtime (needed for migrations potentially)
COPY --from=ghcr.io/astral-sh/uv:latest /uv /bin/uv

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Copy virtual environment from builder
COPY --from=builder /app/.venv /app/.venv

# Copy application code
COPY . .

# Ensure we're using the virtual environment
ENV PATH="/app/.venv/bin:$PATH"

# Run database migrations (will run at startup if needed)
# RUN alembic upgrade head || echo "Migrations will run at startup"

# Expose port
EXPOSE 8082

# Run the application
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082"]

