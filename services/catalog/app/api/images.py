from fastapi import APIRouter, Depends, HTTPException, status, Security, UploadFile, File
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from typing import Optional
from pydantic import BaseModel, Field, HttpUrl
from app.auth.dependencies import require_seller_or_admin
from app.services import get_image_provider
import logging

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/images",
    tags=["Images"]
)

security = HTTPBearer()


class ImageUploadUrlRequest(BaseModel):
    url: HttpUrl = Field(..., description="Image URL to upload to Cloudinary")


class ImageUploadResponse(BaseModel):
    public_id: str = Field(..., description="Cloudinary public_id")
    url: str = Field(..., description="Cloudinary CDN URL")


class ImageUrlResponse(BaseModel):
    url: str = Field(..., description="Cloudinary CDN URL")


@router.post(
    "/upload",
    response_model=ImageUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload image file",
    description="""
    Upload an image file to Cloudinary.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    - File must be a valid image format
    
    **Returns:**
    - public_id: Cloudinary public_id (store this in product.images array)
    - url: Full Cloudinary CDN URL (for immediate display)
    """,
    dependencies=[Depends(security)],
    responses={
        201: {"description": "Image uploaded successfully"},
        400: {"description": "Invalid file or file format"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type"}
    }
)
async def upload_image_file(
    file: UploadFile = File(..., description="Image file to upload"),
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin)
):
    """Upload an image file to Cloudinary"""
    # Validate file type
    if not file.content_type or not file.content_type.startswith('image/'):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File must be an image"
        )
    
    try:
        image_provider = get_image_provider()
        
        # Read file content
        image_data = await file.read()
        
        # Upload to Cloudinary
        public_id = image_provider.upload_image(
            image_data,
            metadata={
                "folder": "products",
                "tags": ["product", "seller-upload"]
            }
        )
        
        if not public_id:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to upload image to Cloudinary"
            )
        
        # Get CDN URL
        url = image_provider.get_image_url(public_id)
        
        return ImageUploadResponse(public_id=public_id, url=url)
        
    except Exception as e:
        logger.error(f"Error uploading image file: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to upload image: {str(e)}"
        )


@router.post(
    "/upload-url",
    response_model=ImageUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload image from URL",
    description="""
    Upload an image from a URL to Cloudinary.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    - URL must point to a valid image
    
    **Returns:**
    - public_id: Cloudinary public_id (store this in product.images array)
    - url: Full Cloudinary CDN URL (for immediate display)
    """,
    dependencies=[Depends(security)],
    responses={
        201: {"description": "Image uploaded successfully"},
        400: {"description": "Invalid URL or failed to fetch image"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type"}
    }
)
async def upload_image_url(
    request: ImageUploadUrlRequest,
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin)
):
    """Upload an image from URL to Cloudinary"""
    try:
        import httpx
        image_provider = get_image_provider()
        
        # Fetch image from URL
        async with httpx.AsyncClient() as client:
            response = await client.get(str(request.url), timeout=30.0)
            response.raise_for_status()
            
            # Validate content type
            content_type = response.headers.get('content-type', '')
            if not content_type.startswith('image/'):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"URL does not point to an image (content-type: {content_type})"
                )
            
            image_data = response.content
        
        # Upload to Cloudinary
        public_id = image_provider.upload_image(
            image_data,
            metadata={
                "folder": "products",
                "tags": ["product", "seller-upload", "from-url"]
            }
        )
        
        if not public_id:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="Failed to upload image to Cloudinary"
            )
        
        # Get CDN URL
        url = image_provider.get_image_url(public_id)
        
        return ImageUploadResponse(public_id=public_id, url=url)
        
    except httpx.HTTPError as e:
        logger.error(f"Error fetching image from URL: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Failed to fetch image from URL: {str(e)}"
        )
    except Exception as e:
        logger.error(f"Error uploading image from URL: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to upload image: {str(e)}"
        )


@router.get(
    "/{public_id}/url",
    response_model=ImageUrlResponse,
    summary="Get image URL",
    description="""
    Get Cloudinary CDN URL for an image by public_id.
    
    **Requirements:**
    - Authentication: Required (JWT token)
    - Account Type: SELLER or ADMIN
    
    **Query Parameters:**
    - variant: Optional transformation variant (e.g., 'thumbnail', 'large')
    """,
    dependencies=[Depends(security)],
    responses={
        200: {"description": "Image URL"},
        401: {"description": "Authentication required"},
        403: {"description": "Requires SELLER or ADMIN account type"}
    }
)
async def get_image_url(
    public_id: str,
    variant: Optional[str] = None,
    credentials: HTTPAuthorizationCredentials = Security(security),
    current_user: dict = Depends(require_seller_or_admin)
):
    """Get Cloudinary CDN URL for an image"""
    try:
        image_provider = get_image_provider()
        url = image_provider.get_image_url(public_id, variant=variant)
        return ImageUrlResponse(url=url)
    except Exception as e:
        logger.error(f"Error getting image URL: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get image URL: {str(e)}"
        )

