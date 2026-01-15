from fastapi import APIRouter, Depends, HTTPException, status, Query, Security
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from typing import List, Optional
from uuid import UUID
from app.db.database import get_db
from app.schemas.product import ProductCreate, ProductUpdate, ProductResponse, ProductListResponse
from app.auth.dependencies import get_current_user, require_seller_or_admin
from app.services.product_service import ProductService

router = APIRouter(
    prefix="/products",
    tags=["Products"]
)

# Security scheme for OpenAPI documentation
security = HTTPBearer()


def get_product_service(db: Session = Depends(get_db)) -> ProductService:
    """Dependency to get product service"""
    return ProductService(db)


@router.post(
    "",
    response_model=ProductResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a new product",
    description="""
    Create a new product. Only sellers and admins can create products.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    - SKU must be unique per seller
    
    **Image Upload:**
    Images should be uploaded to Cloudinary first, then the image IDs (public_ids) 
    should be included in the `images` array.
    
    **Status:**
    - DRAFT: Product is not visible to buyers
    - ACTIVE: Product is visible and can be purchased
    - INACTIVE: Product is hidden but not deleted
    """,
    dependencies=[Depends(security)],
    responses={
        201: {
            "description": "Product created successfully",
            "content": {
                "application/json": {
                    "example": {
                        "id": "123e4567-e89b-12d3-a456-426614174000",
                        "seller_id": "seller@example.com",
                        "sku": "PROD-001",
                        "name": "Example Product",
                        "description": "A great product",
                        "price_cents": 1999,
                        "currency": "USD",
                        "status": "DRAFT",
                        "images": ["image-id-1", "image-id-2"]
                    }
                }
            }
        },
        400: {"description": "Invalid request data"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type"},
        409: {"description": "Product with this SKU already exists"}
    }
)
async def create_product(
    product_data: ProductCreate,
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin),
    product_service: ProductService = Depends(get_product_service)
):
    """Create a product (SELLER or ADMIN only)"""
    try:
        product = product_service.create_product(
            product_data=product_data,
            user_id=current_user.get("user_id"),
            user_email=current_user.get("email")
        )
        return product_service._product_to_response_dict(product)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(e)
        )


@router.get(
    "",
    response_model=ProductListResponse,
    summary="List seller's products",
    description="""
    Get a paginated list of products for the authenticated seller.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    
    **Filtering:**
    - Optionally filter by status (DRAFT, ACTIVE, INACTIVE)
    - Deleted products are excluded from results
    - Returns only the authenticated user's products
    
    **Pagination:**
    - `page`: Page number (1-indexed, default: 1)
    - `page_size`: Number of items per page (default: 20, max: 100)
    
    **Note:** For ADMIN users, use `/api/catalog/products/by-seller/{seller_id}` to get products for a specific seller.
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "Paginated list of products"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type"}
    }
)
async def list_seller_products(
    status: Optional[str] = Query(None, pattern="^(DRAFT|ACTIVE|INACTIVE)$", description="Filter by product status"),
    page: int = Query(1, ge=1, description="Page number (1-indexed)"),
    page_size: int = Query(20, ge=1, le=100, description="Number of items per page"),
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin),
    product_service: ProductService = Depends(get_product_service)
):
    """List seller's products with pagination (returns current user's products only)"""
    import logging
    logger = logging.getLogger(__name__)
    
    try:
        logger.info(f"Listing products for user: {current_user.get('email')} (user_id: {current_user.get('user_id')}), page: {page}, page_size: {page_size}")
        # Note: auth_token is no longer needed - inventory service uses internal API token
        result = await product_service.list_seller_products(
            user_id=current_user.get("user_id"),
            user_email=current_user.get("email"),
            status_filter=status,
            page=page,
            page_size=page_size
        )
        logger.info(f"Found {len(result.products)} products (total: {result.total}) for user {current_user.get('email')}")
        return result
    except Exception as e:
        logger.error(f"Error listing products for user {current_user.get('email')}: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to list products: {str(e)}"
        )


@router.get(
    "/by-seller/{seller_id}",
    response_model=List[ProductResponse],
    summary="List products by seller (ADMIN only)",
    description="""
    Get a list of products for a specific seller. This endpoint is only available to ADMIN users.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: ADMIN only
    
    **Filtering:**
    - Optionally filter by status (DRAFT, ACTIVE, INACTIVE)
    - Deleted products are excluded from results
    
    **Use Case:**
    Allows admins to view products for any seller without needing to authenticate as that seller.
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "List of products for the specified seller"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires ADMIN account type"},
        404: {"description": "Seller not found"}
    }
)
async def list_products_by_seller(
    seller_id: str,
    status: Optional[str] = Query(None, pattern="^(DRAFT|ACTIVE|INACTIVE)$", description="Filter by product status"),
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin),
    product_service: ProductService = Depends(get_product_service)
):
    """List products by seller ID (ADMIN only)"""
    # Only ADMIN can use this endpoint
    if current_user.get("account_type") != "ADMIN":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This endpoint is only available to ADMIN users"
        )
    
    products = product_service.list_products_by_seller_id(
        seller_id=seller_id,
        status_filter=status
    )
    # Convert products to response dicts with image URLs
    return [product_service._product_to_response_dict(product) for product in products]


@router.get(
    "/browse",
    response_model=ProductListResponse,
    summary="Browse products",
    description="""
    Browse and search products. This is a public endpoint - no authentication required.
    
    **Features:**
    - Pagination support
    - Filter by category
    - Only shows ACTIVE, non-deleted products
    - Results ordered by creation date (newest first)
    
    **Pagination:**
    - Default page size: 20
    - Maximum page size: 100
    - Use `has_next` to determine if more pages exist
    """,
    responses={
        200: {"description": "List of products with pagination metadata"}
    }
)
async def browse_products(
    category_ids: Optional[List[UUID]] = Query(None, description="Filter by category UUIDs (multiple categories supported, OR logic)"),
    page: int = Query(1, ge=1, description="Page number (1-indexed)"),
    page_size: int = Query(20, ge=1, le=100, description="Number of items per page"),
    sort_by: Optional[str] = Query("newest", description="Sort order: 'newest' (default) or 'availability' (in stock first)"),
    product_service: ProductService = Depends(get_product_service)
):
    """Browse products (public endpoint)
    
    Supports filtering by multiple categories. Products matching ANY of the provided categories (OR logic) are returned.
    If a top-level category is provided, products from that category and all its subcategories are included.
    
    Sorting options:
    - 'newest': Sort by creation date (newest first, default)
    - 'availability': Sort by availability (in stock first, then out of stock)
    """
    return await product_service.browse_products(
        category_ids=category_ids,
        page=page,
        page_size=page_size,
        sort_by=sort_by
    )


@router.get(
    "/{product_id}",
    response_model=ProductResponse,
    summary="Get product by ID",
    description="""
    Get product details by ID. This is a public endpoint - no authentication required.
    
    **Note:** Only non-deleted products are returned.
    """,
    responses={
        200: {"description": "Product found"},
        404: {"description": "Product not found"}
    }
)
async def get_product(
    product_id: UUID,
    product_service: ProductService = Depends(get_product_service)
):
    """Get product by ID (public endpoint)"""
    try:
        product = product_service.get_product_by_id(product_id)
        return product_service._product_to_response_dict(product)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )


@router.put(
    "/{product_id}",
    response_model=ProductResponse,
    summary="Update a product",
    description="""
    Update an existing product. Sellers can update their own products, admins can update any product.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    - SELLER: Must own the product
    - ADMIN: Can update any product
    
    **Partial Updates:**
    Only provided fields will be updated. Omitted fields remain unchanged.
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "Product updated successfully"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type, or not product owner"},
        404: {"description": "Product not found"}
    }
)
async def update_product(
    product_id: UUID,
    product_data: ProductUpdate,
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin),
    product_service: ProductService = Depends(get_product_service)
):
    """Update a product (SELLER can update their own, ADMIN can update any)"""
    try:
        product = product_service.update_product(
            product_id=product_id,
            product_data=product_data,
            user_id=current_user.get("user_id"),
            user_email=current_user.get("email"),
            account_type=current_user.get("account_type")
        )
        return product_service._product_to_response_dict(product)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )
    except PermissionError as e:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=str(e)
        )


@router.delete(
    "/{product_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a product",
    description="""
    Soft delete a product. Sellers can delete their own products, admins can delete any product.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    - SELLER: Must own the product
    - ADMIN: Can delete any product
    
    **Soft Delete:**
    - Product status is set to DELETED
    - Product is excluded from all queries
    - Product data is retained for order history
    """,
    dependencies=[Depends(security)],
    responses={
        204: {"description": "Product deleted successfully"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type, or not product owner"},
        404: {"description": "Product not found"}
    }
)
async def delete_product(
    product_id: UUID,
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin),
    product_service: ProductService = Depends(get_product_service)
):
    """Soft delete a product (SELLER can delete their own, ADMIN can delete any)"""
    try:
        product_service.delete_product(
            product_id=product_id,
            user_id=current_user.get("user_id"),
            user_email=current_user.get("email"),
            account_type=current_user.get("account_type")
        )
        return None
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )
    except PermissionError as e:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=str(e)
        )


@router.post(
    "/sync-inventory",
    status_code=status.HTTP_200_OK,
    summary="Sync all inventory data (ADMIN only)",
    description="""
    Sync all inventory data from the inventory service to the catalog service's
    denormalized product_inventory table. This endpoint is only available to ADMIN users.
    
    This performs a one-time sync of all active products' inventory information.
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "Inventory sync completed successfully"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires ADMIN account type"},
        500: {"description": "Internal server error during sync"}
    }
)
async def sync_inventory(
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(get_current_user),
    product_service: ProductService = Depends(get_product_service)
):
    """Sync all inventory data (ADMIN only)"""
    import logging
    logger = logging.getLogger(__name__)
    
    # Only ADMIN can use this endpoint
    if current_user.get("account_type") != "ADMIN":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This endpoint is only available to ADMIN users"
        )
    
    try:
        result = await product_service.sync_all_inventory()
        return {
            "message": "Inventory sync completed successfully",
            "synced_count": result["synced_count"],
            "total_products": result["total_products"],
            "products_without_inventory": result["products_without_inventory"]
        }
    except Exception as e:
        logger.error(f"Error syncing inventory: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to sync inventory: {str(e)}"
        )
