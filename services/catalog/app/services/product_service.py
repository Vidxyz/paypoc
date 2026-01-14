from sqlalchemy.orm import Session
from typing import List, Optional
from uuid import UUID
from datetime import datetime, timezone
import logging

from app.models.product import Product
from app.schemas.product import ProductCreate, ProductUpdate, ProductListResponse
from app.services.seller_service import SellerService
from app.kafka.producer import event_producer

logger = logging.getLogger(__name__)


class ProductService:
    """Service layer for product operations"""
    
    def __init__(self, db: Session):
        self.db = db
        self.seller_service = SellerService()
    
    def create_product(
        self,
        product_data: ProductCreate,
        user_id: str,
        user_email: str
    ) -> Product:
        """Create a new product"""
        seller_id = self.seller_service.get_seller_id(user_id, user_email)
        
        # Check if SKU already exists for this seller
        existing = self.db.query(Product).filter(
            Product.seller_id == seller_id,
            Product.sku == product_data.sku,
            Product.deleted_at.is_(None)
        ).first()
        
        if existing:
            raise ValueError(f"Product with SKU '{product_data.sku}' already exists")
        
        # Create product
        product = Product(
            seller_id=seller_id,
            sku=product_data.sku,
            name=product_data.name,
            description=product_data.description,
            category_id=product_data.category_id,
            price_cents=product_data.price_cents,
            currency=product_data.currency,
            status=product_data.status,
            attributes=product_data.attributes,
            images=product_data.images or []
        )
        
        self.db.add(product)
        self.db.commit()
        self.db.refresh(product)
        
        # Publish ProductCreatedEvent to Kafka
        try:
            event_producer.publish_product_created(
                product_id=str(product.id),
                seller_id=seller_id,
                sku=product.sku,
                name=product.name
            )
            event_producer.flush()
        except Exception as e:
            logger.error(f"Failed to publish ProductCreatedEvent: {e}", exc_info=True)
        
        return product
    
    def list_seller_products(
        self,
        user_id: str,
        user_email: str,
        status_filter: Optional[str] = None
    ) -> List[Product]:
        """List products for a specific seller"""
        seller_id = self.seller_service.get_seller_id(user_id, user_email)
        
        query = self.db.query(Product).filter(
            Product.seller_id == seller_id,
            Product.deleted_at.is_(None)
        )
        
        if status_filter:
            query = query.filter(Product.status == status_filter)
        
        return query.order_by(Product.created_at.desc()).all()
    
    def list_products_by_seller_id(
        self,
        seller_id: str,
        status_filter: Optional[str] = None
    ) -> List[Product]:
        """List products for a specific seller ID (admin only)"""
        query = self.db.query(Product).filter(
            Product.seller_id == seller_id,
            Product.deleted_at.is_(None)
        )
        
        if status_filter:
            query = query.filter(Product.status == status_filter)
        
        return query.order_by(Product.created_at.desc()).all()
    
    def get_product_by_id(self, product_id: UUID) -> Product:
        """Get a product by ID"""
        product = self.db.query(Product).filter(
            Product.id == product_id,
            Product.deleted_at.is_(None)
        ).first()
        
        if not product:
            raise ValueError("Product not found")
        
        return product
    
    def update_product(
        self,
        product_id: UUID,
        product_data: ProductUpdate,
        user_id: str,
        user_email: str,
        account_type: str
    ) -> Product:
        """Update a product"""
        product = self.db.query(Product).filter(
            Product.id == product_id,
            Product.deleted_at.is_(None)
        ).first()
        
        if not product:
            raise ValueError("Product not found")
        
        # If not ADMIN, verify ownership
        if account_type != "ADMIN":
            seller_id = self.seller_service.get_seller_id(user_id, user_email)
            if product.seller_id != seller_id:
                raise PermissionError("You can only update your own products")
        
        # Update fields
        update_data = product_data.dict(exclude_unset=True)
        for field, value in update_data.items():
            setattr(product, field, value)
        
        self.db.commit()
        self.db.refresh(product)
        
        # Publish ProductUpdatedEvent to Kafka
        try:
            event_producer.publish_product_updated(
                product_id=str(product.id),
                seller_id=product.seller_id,
                sku=product.sku,
                name=product.name
            )
            event_producer.flush()
        except Exception as e:
            logger.error(f"Failed to publish ProductUpdatedEvent: {e}", exc_info=True)
        
        return product
    
    def delete_product(
        self,
        product_id: UUID,
        user_id: str,
        user_email: str,
        account_type: str
    ) -> None:
        """Soft delete a product"""
        product = self.db.query(Product).filter(
            Product.id == product_id,
            Product.deleted_at.is_(None)
        ).first()
        
        if not product:
            raise ValueError("Product not found")
        
        # If not ADMIN, verify ownership
        if account_type != "ADMIN":
            seller_id = self.seller_service.get_seller_id(user_id, user_email)
            if product.seller_id != seller_id:
                raise PermissionError("You can only delete your own products")
        
        # Soft delete
        product.status = "DELETED"
        product.deleted_at = datetime.now(timezone.utc)
        
        self.db.commit()
        
        # Publish ProductDeletedEvent to Kafka
        try:
            event_producer.publish_product_deleted(
                product_id=str(product.id),
                seller_id=product.seller_id,
                sku=product.sku
            )
            event_producer.flush()
        except Exception as e:
            logger.error(f"Failed to publish ProductDeletedEvent: {e}", exc_info=True)
    
    def browse_products(
        self,
        category_id: Optional[UUID] = None,
        page: int = 1,
        page_size: int = 20
    ) -> ProductListResponse:
        """Browse products with pagination"""
        query = self.db.query(Product).filter(
            Product.status == "ACTIVE",
            Product.deleted_at.is_(None)
        )
        
        if category_id:
            query = query.filter(Product.category_id == category_id)
        
        total = query.count()
        products = query.order_by(Product.created_at.desc()).offset(
            (page - 1) * page_size
        ).limit(page_size).all()
        
        return ProductListResponse(
            products=products,
            total=total,
            page=page,
            page_size=page_size,
            has_next=(page * page_size) < total
        )

