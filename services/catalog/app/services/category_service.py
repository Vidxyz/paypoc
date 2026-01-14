from sqlalchemy.orm import Session
from typing import List
from uuid import UUID

from app.models.category import Category


class CategoryService:
    """Service layer for category operations"""
    
    def __init__(self, db: Session):
        self.db = db
    
    def list_categories(self) -> List[Category]:
        """List all top-level categories"""
        return self.db.query(Category).filter(Category.parent_id.is_(None)).all()
    
    def get_category_by_id(self, category_id: UUID) -> Category:
        """Get a category by ID"""
        category = self.db.query(Category).filter(Category.id == category_id).first()
        
        if not category:
            raise ValueError("Category not found")
        
        return category

