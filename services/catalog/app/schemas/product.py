from pydantic import BaseModel, Field, validator
from typing import Optional, List, Dict, Any
from uuid import UUID
from datetime import datetime


class ProductCreate(BaseModel):
    sku: str = Field(..., min_length=1, max_length=255, description="Product SKU (unique per seller)", example="PROD-001")
    name: str = Field(..., min_length=1, max_length=500, description="Product name", example="Example Product")
    description: Optional[str] = Field(None, description="Product description", example="A great product")
    category_id: Optional[UUID] = Field(None, description="Category UUID", example="123e4567-e89b-12d3-a456-426614174000")
    price_cents: int = Field(..., gt=0, description="Price in cents", example=1999)
    currency: str = Field(..., pattern="^[A-Z]{3}$", description="Currency code (ISO 4217)", example="USD")
    status: str = Field(default="DRAFT", pattern="^(DRAFT|ACTIVE|INACTIVE)$", description="Product status", example="DRAFT")
    attributes: Optional[Dict[str, Any]] = Field(None, description="Additional product attributes (JSON)", example={"color": "red", "size": "L"})
    images: Optional[List[str]] = Field(None, description="List of image IDs (Cloudinary public_ids)", example=["image-id-1", "image-id-2"])


class ProductUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=500, description="Product name", example="Updated Product Name")
    description: Optional[str] = Field(None, description="Product description", example="Updated description")
    category_id: Optional[UUID] = Field(None, description="Category UUID", example="123e4567-e89b-12d3-a456-426614174000")
    price_cents: Optional[int] = Field(None, gt=0, description="Price in cents", example=2499)
    currency: Optional[str] = Field(None, pattern="^[A-Z]{3}$", description="Currency code (ISO 4217)", example="USD")
    status: Optional[str] = Field(None, pattern="^(DRAFT|ACTIVE|INACTIVE)$", description="Product status", example="ACTIVE")
    attributes: Optional[Dict[str, Any]] = Field(None, description="Additional product attributes (JSON)", example={"color": "blue"})
    images: Optional[List[str]] = Field(None, description="List of image IDs (Cloudinary public_ids)", example=["new-image-id"])


class ProductResponse(BaseModel):
    id: UUID = Field(..., description="Product UUID", example="123e4567-e89b-12d3-a456-426614174000")
    seller_id: str = Field(..., description="Seller ID (email)", example="seller@example.com")
    sku: str = Field(..., description="Product SKU", example="PROD-001")
    name: str = Field(..., description="Product name", example="Example Product")
    description: Optional[str] = Field(None, description="Product description")
    category_id: Optional[UUID] = Field(None, description="Category UUID")
    price_cents: int = Field(..., description="Price in cents", example=1999)
    currency: str = Field(..., description="Currency code", example="USD")
    status: str = Field(..., description="Product status", example="ACTIVE")
    attributes: Optional[Dict[str, Any]] = Field(None, description="Additional attributes")
    images: Optional[List[str]] = Field(None, description="List of image URLs (Cloudinary CDN URLs)")
    created_at: datetime = Field(..., description="Creation timestamp")
    updated_at: datetime = Field(..., description="Last update timestamp")
    
    class Config:
        from_attributes = True
        json_schema_extra = {
            "example": {
                "id": "123e4567-e89b-12d3-a456-426614174000",
                "seller_id": "seller@example.com",
                "sku": "PROD-001",
                "name": "Example Product",
                "description": "A great product",
                "category_id": "123e4567-e89b-12d3-a456-426614174001",
                "price_cents": 1999,
                "currency": "USD",
                "status": "ACTIVE",
                "attributes": {"color": "red", "size": "L"},
                "images": ["https://res.cloudinary.com/dwuu3lcth/image/upload/v1/products/image-id-1", "https://res.cloudinary.com/dwuu3lcth/image/upload/v1/products/image-id-2"],
                "created_at": "2024-01-15T10:30:00Z",
                "updated_at": "2024-01-15T10:30:00Z"
            }
        }


class ProductListResponse(BaseModel):
    products: List[ProductResponse]
    total: int
    page: int
    page_size: int
    has_next: bool

