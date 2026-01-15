from sqlalchemy import Column, Integer, DateTime, ForeignKey
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.sql import func
from sqlalchemy.orm import relationship
from app.db.database import Base


class ProductInventory(Base):
    """Denormalized inventory cache for efficient sorting and querying
    
    This table is maintained by consuming Kafka events from the inventory service.
    It allows database-level sorting by availability without needing to join
    across services.
    """
    __tablename__ = "product_inventory"
    
    product_id = Column(UUID(as_uuid=True), ForeignKey('products.id', ondelete='CASCADE'), primary_key=True)
    available_quantity = Column(Integer, nullable=False, default=0)
    total_quantity = Column(Integer, nullable=False, default=0)
    reserved_quantity = Column(Integer, nullable=False, default=0)
    allocated_quantity = Column(Integer, nullable=False, default=0)
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False)
    
    # Relationship (optional, for convenience)
    product = relationship("Product", back_populates="inventory_cache")
