from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.utils import get_openapi
from contextlib import asynccontextmanager
import logging

from app.db.database import init_db
from app.api import products, categories, health, images

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    logger.info("Starting Catalog Service...")
    await init_db()
    logger.info("Catalog Service started successfully")
    yield
    # Shutdown
    logger.info("Shutting down Catalog Service...")


app = FastAPI(
    title="Catalog Service",
    description="""
    Product catalog management and browsing for BuyIt platform.
    
    **Features:**
    - Product CRUD operations (sellers)
    - Product browsing and listing (buyers)
    - Category management
    - Cloudinary image integration
    - Kafka event publishing
    - Auth0 JWT authentication
    
    **Authentication:**
    Most endpoints require JWT authentication via Auth0. Include the token in the Authorization header:
    ```
    Authorization: Bearer <your-jwt-token>
    ```
    
    **Account Types:**
    - **SELLER**: Can create, update, and delete their own products
    - **BUYER**: Can browse and view products
    - **ADMIN**: Full access to all products
    """,
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json"
)


def custom_openapi():
    """Custom OpenAPI schema with JWT Bearer authentication"""
    if app.openapi_schema:
        return app.openapi_schema
    
    openapi_schema = get_openapi(
        title=app.title,
        version=app.version,
        description=app.description,
        routes=app.routes,
    )
    
    # Add JWT Bearer authentication
    openapi_schema["components"]["securitySchemes"] = {
        "BearerAuth": {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT",
            "description": "JWT token from Auth0. Format: Bearer <token>"
        }
    }
    
    # Apply security to all endpoints (individual endpoints can override)
    # Note: Public endpoints like /health and /products/browse don't require auth
    # and will be handled by the endpoint's dependencies
    
    app.openapi_schema = openapi_schema
    return app.openapi_schema


app.openapi = custom_openapi

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # todo-vh: Configure appropriately for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(health.router)
app.include_router(products.router, prefix="/api/catalog")
app.include_router(categories.router, prefix="/api/catalog")
app.include_router(images.router, prefix="/api/catalog")


@app.get("/")
async def root():
    return {"service": "catalog-service", "version": "1.0.0"}

