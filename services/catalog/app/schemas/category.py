from pydantic import BaseModel, Field
from typing import Optional
from uuid import UUID
from datetime import datetime


class CategoryCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    slug: str = Field(..., min_length=1, max_length=255, pattern="^[a-z0-9-]+$")
    description: Optional[str] = None
    parent_id: Optional[UUID] = None


class CategoryUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=255)
    slug: Optional[str] = Field(None, min_length=1, max_length=255, pattern="^[a-z0-9-]+$")
    description: Optional[str] = None
    parent_id: Optional[UUID] = None


class CategoryResponse(BaseModel):
    id: UUID = Field(..., description="Category UUID", example="123e4567-e89b-12d3-a456-426614174000")
    name: str = Field(..., description="Category name", example="Electronics")
    slug: str = Field(..., description="Category URL slug", example="electronics")
    description: Optional[str] = Field(None, description="Category description")
    parent_id: Optional[UUID] = Field(None, description="Parent category UUID (for hierarchical categories)")
    created_at: datetime = Field(..., description="Creation timestamp")
    
    class Config:
        from_attributes = True
        json_schema_extra = {
            "example": {
                "id": "123e4567-e89b-12d3-a456-426614174000",
                "name": "Electronics",
                "slug": "electronics",
                "description": "Electronic products",
                "parent_id": None,
                "created_at": "2024-01-15T10:30:00Z"
            }
        }

