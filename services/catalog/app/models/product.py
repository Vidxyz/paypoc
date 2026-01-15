from sqlalchemy import Column, String, BigInteger, Text, DateTime, CheckConstraint, Index
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.sql import func
from sqlalchemy.orm import relationship
import uuid
from app.db.database import Base


class Product(Base):
    __tablename__ = "products"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    seller_id = Column(Text, nullable=False)
    sku = Column(Text, nullable=False)
    name = Column(Text, nullable=False)
    description = Column(Text)
    category_id = Column(UUID(as_uuid=True))
    price_cents = Column(BigInteger, nullable=False)
    currency = Column(String(3), nullable=False)
    status = Column(String(20), nullable=False, default="DRAFT")
    attributes = Column(JSONB)
    images = Column(JSONB)  # Array of image IDs (provider-specific, e.g. Cloudinary public_id)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False)
    deleted_at = Column(DateTime(timezone=True))
    
    __table_args__ = (
        CheckConstraint("price_cents > 0", name="positive_price"),
        CheckConstraint("currency ~ '^[A-Z]{3}$'", name="currency_format"),
        CheckConstraint("status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DELETED')", name="status_valid"),
        Index("idx_seller_status", "seller_id", "status"),
        Index("idx_category", "category_id"),
        Index("idx_status", "status", postgresql_where=deleted_at.is_(None)),
        {"schema": None}  # Use default schema
    )
    
    # Relationship to denormalized inventory cache
    inventory_cache = relationship("ProductInventory", back_populates="product", uselist=False)

