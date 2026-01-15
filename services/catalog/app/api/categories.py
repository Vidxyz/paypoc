from fastapi import APIRouter, Depends, HTTPException, status, Security
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from typing import List
from uuid import UUID
from app.db.database import get_db
from app.schemas.category import CategoryResponse
from app.auth.dependencies import get_current_user
from app.services.category_service import CategoryService

router = APIRouter(
    prefix="/categories",
    tags=["Categories"]
)

# Security scheme for OpenAPI documentation
security = HTTPBearer()


def get_category_service(db: Session = Depends(get_db)) -> CategoryService:
    """Dependency to get category service"""
    return CategoryService(db)


@router.get(
    "",
    response_model=List[CategoryResponse],
    summary="List all categories",
    description="""
    Get a list of all categories (top-level and subcategories), ordered hierarchically.
    Categories are ordered by parent (top-level first) then by name.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: ADMIN, BUYER, or SELLER
    
    **Response:**
    - Returns all categories with parent_id indicating hierarchy
    - Top-level categories have parent_id = null
    - Subcategories have parent_id pointing to their parent category
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "List of categories"},
        401: {"description": "Authentication required"}
    }
)
async def list_categories(
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(get_current_user),
    category_service: CategoryService = Depends(get_category_service)
):
    """List all categories (requires authentication - all account types)"""
    categories = category_service.list_categories()
    return categories


@router.get(
    "/{category_id}",
    response_model=CategoryResponse,
    summary="Get category by ID",
    description="""
    Get category details by ID.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: ADMIN, BUYER, or SELLER
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "Category found"},
        401: {"description": "Authentication required"},
        404: {"description": "Category not found"}
    }
)
async def get_category(
    category_id: UUID,
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(get_current_user),
    category_service: CategoryService = Depends(get_category_service)
):
    """Get category by ID (requires authentication - all account types)"""
    try:
        category = category_service.get_category_by_id(category_id)
        return category
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )
