# Catalog Service

FastAPI-based service for product catalog management and browsing.

## Features

- Product CRUD operations (sellers)
- Product browsing and listing (buyers)
- Category management
- Cloudinary image integration (with abstracted image provider interface)
- Kafka event publishing
- Auth0 JWT authentication

## Technology Stack

- FastAPI (Python 3.11)
- PostgreSQL
- Alembic (migrations)
- Kafka (events)
- Auth0 (authentication)
- uv (Python package manager)
- pyproject.toml (dependency management)

## Setup

1. Install dependencies:
```bash
uv sync
```

**Note**: This service uses `uv` (a fast Python package installer) with `pyproject.toml` for dependency management. Make sure `uv` is installed:
```bash
# Install uv (if not already installed)
curl -LsSf https://astral.sh/uv/install.sh | sh
```

`uv sync` will:
- Read dependencies from `pyproject.toml`
- Create/update a virtual environment
- Install all dependencies
- Generate/update `uv.lock` lock file

2. Configure environment variables:
   - Copy `.env.test` to `.env`: `cp .env.test .env`
   - Update `.env` with your actual values (especially Auth0 and Cloudinary credentials)
   - The `.env` file is gitignored and should NOT be committed

3. Run database migrations:
```bash
alembic upgrade head
```

4. Run the service:
```bash
uvicorn app.main:app --reload --port 8082
```

### Cloudinary Setup

The service uses Cloudinary for image storage. To configure:

1. Create a free account at [cloudinary.com](https://cloudinary.com/)
2. Get your credentials from the [Dashboard](https://cloudinary.com/console):
   - **Cloud Name**: Found on the Dashboard
   - **API Key**: Settings → Security → API Keys
   - **API Secret**: Settings → Security → API Keys (click "Reveal")
3. Add them to your `.env` file:
   ```
   CLOUDINARY_CLOUD_NAME=your-cloud-name
   CLOUDINARY_API_KEY=your-api-key
   CLOUDINARY_API_SECRET=your-api-secret
   ```

**Note**: Image operations will fail gracefully with warnings if Cloudinary is not configured.

## API Documentation

Once running, access OpenAPI/Swagger documentation at:
- **Swagger UI**: `http://localhost:8082/docs` - Interactive API testing interface
- **ReDoc**: `http://localhost:8082/redoc` - Alternative documentation view
- **OpenAPI JSON**: `http://localhost:8082/openapi.json` - OpenAPI specification

The Swagger UI provides:
- Complete API documentation with request/response schemas
- Interactive "Try it out" functionality to test endpoints
- Authentication support (click "Authorize" button to add JWT token)
- Example values for all fields
- Response examples and error codes

## Database Migrations

Migrations are managed via Alembic:
```bash
# Create a new migration
alembic revision --autogenerate -m "description"

# Apply migrations
alembic upgrade head

# Rollback
alembic downgrade -1
```

