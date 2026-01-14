from fastapi import APIRouter
from pydantic import BaseModel, Field

router = APIRouter(tags=["Health"])


class HealthResponse(BaseModel):
    status: str = Field(..., description="Service health status", example="healthy")
    service: str = Field(..., description="Service name", example="catalog-service")
    version: str = Field(..., description="Service version", example="1.0.0")


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="Health check",
    description="""
    Health check endpoint for monitoring and load balancer health checks.
    
    Returns the service status, name, and version.
    """,
    responses={
        200: {
            "description": "Service is healthy",
            "content": {
                "application/json": {
                    "example": {
                        "status": "healthy",
                        "service": "catalog-service",
                        "version": "1.0.0"
                    }
                }
            }
        }
    }
)
async def health():
    """Health check endpoint"""
    return HealthResponse(
        status="healthy",
        service="catalog-service",
        version="1.0.0"
    )

