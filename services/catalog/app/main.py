from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.utils import get_openapi
from fastapi.responses import Response, JSONResponse
from fastapi.exceptions import RequestValidationError
from contextlib import asynccontextmanager
import logging
import traceback

from app.db.database import init_db
from app.api import products, categories, health, images
from app.kafka.inventory_consumer import get_inventory_consumer
import threading

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
    
    # Start inventory event consumer in background thread
    logger.info("=" * 80)
    logger.info("CATALOG SERVICE: Initializing Kafka inventory event consumer...")
    logger.info("=" * 80)
    
    try:
        from app.config import settings
        logger.info(f"CATALOG SERVICE: Kafka config - bootstrap_servers={settings.kafka_bootstrap_servers}")
        logger.info(f"CATALOG SERVICE: Kafka config - inventory_events_topic={settings.kafka_inventory_events_topic}")
        logger.info(f"CATALOG SERVICE: Kafka config - events_topic={settings.kafka_events_topic}")
    except Exception as e:
        logger.error(f"CATALOG SERVICE: Error reading Kafka config: {e}", exc_info=True)
    
    try:
        inventory_consumer = get_inventory_consumer()
        logger.info("CATALOG SERVICE: Consumer instance created successfully")
    except Exception as e:
        logger.error(f"CATALOG SERVICE: Failed to create consumer instance: {e}", exc_info=True)
        raise
    
    logger.info("CATALOG SERVICE: Creating background thread for inventory event consumer...")
    
    def start_consumer_with_logging():
        """Wrapper to start consumer with proper error handling and logging"""
        import logging
        import sys
        
        print("KAFKA CONSUMER THREAD: Thread function called", flush=True)
        
        # Ensure logging is configured for this thread with immediate flushing
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
            stream=sys.stdout,
            force=True  # Force reconfiguration
        )
        
        print("KAFKA CONSUMER THREAD: Logging configured", flush=True)
        
        # Get the logger for this module and ensure it flushes
        consumer_logger = logging.getLogger("app.kafka.inventory_consumer")
        consumer_logger.setLevel(logging.INFO)
        for handler in consumer_logger.handlers:
            if hasattr(handler, 'stream'):
                handler.stream = sys.stdout
        
        print("KAFKA CONSUMER THREAD: About to call inventory_consumer.start()", flush=True)
        
        try:
            inventory_consumer.start()
            print("KAFKA CONSUMER THREAD: consumer.start() returned (should not happen)", flush=True)
        except Exception as e:
            print(f"KAFKA CONSUMER THREAD: Fatal error in consumer: {e}", flush=True)
            consumer_logger.error(f"KAFKA CONSUMER THREAD: Fatal error in consumer: {e}", exc_info=True)
            sys.stdout.flush()
            raise
    
    consumer_thread = threading.Thread(
        target=start_consumer_with_logging,
        daemon=True,
        name="inventory-consumer"
    )
    
    try:
        consumer_thread.start()
        logger.info("CATALOG SERVICE: Started inventory event consumer in background thread")
        logger.info("CATALOG SERVICE: Thread ID: %s", consumer_thread.ident)
        logger.info("CATALOG SERVICE: Thread name: %s", consumer_thread.name)
        logger.info("CATALOG SERVICE: Thread is alive: %s", consumer_thread.is_alive())
        logger.info("CATALOG SERVICE: Thread is daemon: %s", consumer_thread.daemon)
        
        # Give the thread a moment to start and log
        import time
        time.sleep(0.5)
        logger.info("CATALOG SERVICE: Thread is still alive after 0.5s: %s", consumer_thread.is_alive())
    except Exception as e:
        logger.error(f"CATALOG SERVICE: Failed to start consumer thread: {e}", exc_info=True)
        raise
    
    logger.info("=" * 80)
    
    logger.info("Catalog Service started successfully")
    yield
    # Shutdown
    logger.info("Shutting down Catalog Service...")
    inventory_consumer.stop()
    logger.info("Stopped inventory event consumer")


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

# Allowed origins for CORS
ALLOWED_ORIGINS = [
    "https://seller.local",
    "https://admin.local",
    "https://buyit.local",
    "http://seller.local",
    "http://admin.local",
    "http://buyit.local",
]

# CORS middleware - MUST be added immediately after app creation
# In FastAPI, middleware is executed in reverse order (last added = first executed)
# So CORS should be added first to ensure it processes requests before other middleware
# Note: When allow_credentials=True, we cannot use allow_headers=["*"] per CORS spec
# So we need to explicitly list headers or use a custom handler
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"],
    allow_headers=["Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With", "Access-Control-Request-Method", "Access-Control-Request-Headers"],
    expose_headers=["*"],
    max_age=3600,
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


# Global exception handler for unhandled exceptions
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """Catch all unhandled exceptions and log them properly"""
    logger.error(
        f"Unhandled exception: {type(exc).__name__}: {str(exc)}",
        exc_info=True,
        extra={
            "path": request.url.path,
            "method": request.method,
            "headers": dict(request.headers),
        }
    )
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "detail": "Internal server error",
            "error_type": type(exc).__name__,
            "message": str(exc)
        }
    )


# Validation error handler
@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Handle request validation errors"""
    logger.warning(
        f"Validation error: {exc.errors()}",
        extra={
            "path": request.url.path,
            "method": request.method,
        }
    )
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={"detail": exc.errors()}
    )


# Custom OPTIONS handler for CORS preflight
# This ensures OPTIONS requests are handled correctly when allow_credentials=True
# Per CORS spec, we must echo back the requested headers, not use "*"
@app.options("/{full_path:path}")
async def options_handler(request: Request, full_path: str):
    """Handle CORS preflight OPTIONS requests"""
    origin = request.headers.get("Origin")
    
    # Check if origin is allowed
    if origin and origin in ALLOWED_ORIGINS:
        # Get requested headers and method from preflight request
        requested_method = request.headers.get("Access-Control-Request-Method", "GET")
        requested_headers = request.headers.get("Access-Control-Request-Headers", "")
        
        # Build response headers - must echo back requested headers, not use "*"
        response_headers = {
            "Access-Control-Allow-Origin": origin,
            "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD",
            "Access-Control-Allow-Credentials": "true",
            "Access-Control-Max-Age": "3600",
        }
        
        # Echo back the requested headers (required when credentials=true)
        if requested_headers:
            response_headers["Access-Control-Allow-Headers"] = requested_headers
        else:
            response_headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, Accept, Origin, X-Requested-With"
        
        return Response(status_code=200, headers=response_headers)
    
    # Origin not allowed
    return Response(status_code=403)

# Include routers
app.include_router(health.router)
app.include_router(products.router, prefix="/api/catalog")
app.include_router(categories.router, prefix="/api/catalog")
app.include_router(images.router, prefix="/api/catalog")


@app.get("/")
async def root():
    return {"service": "catalog-service", "version": "1.0.0"}

