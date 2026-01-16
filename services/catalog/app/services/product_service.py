from sqlalchemy.orm import Session
from sqlalchemy import case
from typing import List, Optional
from uuid import UUID
from datetime import datetime, timezone
import logging
import asyncio

from app.models.product import Product
from app.schemas.product import ProductCreate, ProductUpdate, ProductListResponse
from app.services.seller_service import SellerService
from app.services import get_image_provider
from app.services.inventory_client import get_inventory_client
from app.kafka.producer import event_producer

logger = logging.getLogger(__name__)


class ProductService:
    """Service layer for product operations"""
    
    def __init__(self, db: Session):
        self.db = db
        self.seller_service = SellerService()
        self.image_provider = get_image_provider()
    
    def _convert_image_ids_to_urls(self, image_ids: List[str]) -> List[str]:
        """Convert image public_ids to Cloudinary CDN URLs"""
        if not image_ids:
            return []
        try:
            return [self.image_provider.get_image_url(public_id) for public_id in image_ids]
        except Exception as e:
            logger.warning(f"Failed to convert some image IDs to URLs: {e}")
            # Return empty list if conversion fails
            return []
    
    def _product_to_response_dict(self, product: Product, inventory: Optional[dict] = None) -> dict:
        """Convert Product model to dict with image URLs instead of IDs
        
        Args:
            product: Product model instance
            inventory: Optional inventory information dict
        """
        product_dict = {
            "id": product.id,
            "seller_id": product.seller_id,
            "sku": product.sku,
            "name": product.name,
            "description": product.description,
            "category_id": product.category_id,
            "price_cents": product.price_cents,
            "currency": product.currency,
            "status": product.status,
            "attributes": product.attributes,
            "images": self._convert_image_ids_to_urls(product.images) if product.images else [],
            "created_at": product.created_at,
            "updated_at": product.updated_at,
        }
        
        # Add inventory if provided
        if inventory:
            product_dict["inventory"] = inventory
        
        return product_dict
    
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
        
        # Validate category_id if provided
        if product_data.category_id:
            from app.models.category import Category
            category = self.db.query(Category).filter(Category.id == product_data.category_id).first()
            if not category:
                raise ValueError(f"Category not found: {product_data.category_id}")
        
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
    
    async def list_seller_products(
        self,
        user_id: str,
        user_email: str,
        status_filter: Optional[str] = None,
        page: int = 1,
        page_size: int = 20,
        auth_token: Optional[str] = None  # Deprecated: kept for backward compatibility, not used for inventory
    ) -> ProductListResponse:
        """List products for a specific seller with pagination and inventory information"""
        try:
            logger.info(f"Getting seller_id for user_id: {user_id}, email: {user_email}")
            seller_id = self.seller_service.get_seller_id(user_id, user_email)
            logger.info(f"Found seller_id: {seller_id} for user {user_email}")
            
            query = self.db.query(Product).filter(
                Product.seller_id == seller_id,
                Product.deleted_at.is_(None)
            )
            
            if status_filter:
                logger.debug(f"Filtering by status: {status_filter}")
                query = query.filter(Product.status == status_filter)
            
            # Get total count
            total = query.count()
            
            # Apply pagination
            products = query.order_by(Product.created_at.desc()).offset(
                (page - 1) * page_size
            ).limit(page_size).all()
            
            logger.info(f"Found {len(products)} products (page {page}, total: {total}) for seller_id: {seller_id}")
            
            # Fetch inventory for all products in batch (using internal API token)
            inventory_map = {}
            if products:
                try:
                    inventory_client = get_inventory_client()
                    product_ids = [p.id for p in products]
                    logger.debug(f"Fetching inventory for {len(product_ids)} products: {product_ids}")
                    inventory_map = await inventory_client.get_stock_batch(product_ids)
                    inventory_count = len([v for v in inventory_map.values() if v is not None])
                    logger.info(f"Successfully fetched inventory for {inventory_count} out of {len(product_ids)} products")
                except Exception as e:
                    logger.error(f"Failed to fetch inventory information: {e}", exc_info=True)
                    # Continue without inventory - it's optional
            
            # Build product response dicts with inventory
            product_dicts = []
            for product in products:
                inventory = inventory_map.get(product.id)
                product_dicts.append(self._product_to_response_dict(product, inventory))
            
            return ProductListResponse(
                products=product_dicts,
                total=total,
                page=page,
                page_size=page_size,
                has_next=(page * page_size) < total
            )
        except Exception as e:
            logger.error(f"Error in list_seller_products for user {user_email}: {e}", exc_info=True)
            raise
    
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
    
    def get_product_by_id(self, product_id: UUID) -> dict:
        """Get a product by ID with inventory from denormalized table"""
        from sqlalchemy.orm import joinedload
        from app.models.product_inventory import ProductInventory
        
        # Query product with inventory cache joined
        product = self.db.query(Product).outerjoin(
            ProductInventory,
            Product.id == ProductInventory.product_id
        ).options(joinedload(Product.inventory_cache)).filter(
            Product.id == product_id,
            Product.deleted_at.is_(None)
        ).first()
        
        if not product:
            raise ValueError("Product not found")
        
        # Get inventory from denormalized table (if available)
        inventory_data = None
        if product.inventory_cache:
            inv = product.inventory_cache
            inventory_data = {
                "inventory_id": None,  # Not stored in cache
                "available_quantity": inv.available_quantity,
                "total_quantity": inv.total_quantity,
                "reserved_quantity": inv.reserved_quantity,
                "allocated_quantity": inv.allocated_quantity,
            }
        
        return self._product_to_response_dict(product, inventory_data)
    
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
        
        # Validate category_id if provided
        if product_data.category_id is not None:
            from app.models.category import Category
            category = self.db.query(Category).filter(Category.id == product_data.category_id).first()
            if not category:
                raise ValueError(f"Category not found: {product_data.category_id}")
        
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
    
    async     def browse_products(
        self,
        category_ids: Optional[List[UUID]] = None,
        page: int = 1,
        page_size: int = 20,
        sort_by: str = "newest"
    ) -> ProductListResponse:
        """Browse products with pagination and inventory information
        
        If category_ids is provided (list of category UUIDs):
        - For each category: if it's a top-level category, includes products from that category and all its subcategories
        - If it's a subcategory, includes only products from that subcategory
        - Products matching ANY of the provided categories (OR logic) are returned
        """
        query = self.db.query(Product).filter(
            Product.status == "ACTIVE",
            Product.deleted_at.is_(None)
        )
        
        if category_ids:
            from app.models.category import Category
            all_category_ids = set()
            
            # Process each category ID
            for category_id in category_ids:
                category = self.db.query(Category).filter(Category.id == category_id).first()
                
                if category:
                    # Get all subcategory IDs for this category
                    subcategories = self.db.query(Category).filter(
                        Category.parent_id == category_id
                    ).all()
                    subcategory_ids = [subcat.id for subcat in subcategories]
                    
                    # Include the category itself and all its subcategories
                    all_category_ids.add(category_id)
                    all_category_ids.update(subcategory_ids)
            
            # Filter products that match ANY of the categories (OR logic)
            if all_category_ids:
                query = query.filter(Product.category_id.in_(list(all_category_ids)))
            else:
                # No valid categories found, return empty results
                query = query.filter(Product.category_id == UUID('00000000-0000-0000-0000-000000000000'))
        
        # Join with product_inventory for availability sorting and to include inventory in response
        from app.models.product_inventory import ProductInventory
        from sqlalchemy.orm import joinedload
        
        # Always join to get inventory data in response (LEFT OUTER JOIN)
        query = query.outerjoin(
            ProductInventory,
            Product.id == ProductInventory.product_id
        ).options(joinedload(Product.inventory_cache))
        
        total = query.count()
        
        # Apply sorting
        if sort_by == "availability":
            # Database-level sorting: in-stock first (available_quantity > 0), then by quantity descending, then by creation date
            # Use CASE to prioritize in-stock items, then sort by available_quantity DESC
            query = query.order_by(
                # In-stock products first (available_quantity > 0 gets 0, out-of-stock/null gets 1)
                case(
                    (ProductInventory.available_quantity > 0, 0),
                    else_=1
                ).asc(),
                # Then by available quantity descending (higher quantity first)
                ProductInventory.available_quantity.desc().nulls_last(),
                # Then by creation date (newer first)
                Product.created_at.desc()
            )
        else:
            # Default: newest first
            query = query.order_by(Product.created_at.desc())
        
        # Apply pagination
        products = query.offset((page - 1) * page_size).limit(page_size).all()
        
        # Convert products to response dicts with image URLs and inventory
        product_dicts = []
        for product in products:
            # Get inventory from joined table (if available) or None
            inventory_data = None
            if product.inventory_cache:
                inv = product.inventory_cache
                inventory_data = {
                    "inventory_id": None,  # Not stored in cache
                    "available_quantity": inv.available_quantity,
                    "total_quantity": inv.total_quantity,
                    "reserved_quantity": inv.reserved_quantity,
                    "allocated_quantity": inv.allocated_quantity,
                }
            product_dicts.append(self._product_to_response_dict(product, inventory_data))
        
        return ProductListResponse(
            products=product_dicts,
            total=total,
            page=page,
            page_size=page_size,
            has_next=(page * page_size) < total
        )
    
    async def sync_all_inventory(self) -> dict:
        """Sync all inventory data from inventory service to catalog database
        
        This method fetches all active products and syncs their inventory
        data from the inventory service into the denormalized product_inventory table.
        
        Returns:
            dict with sync statistics (synced_count, total_products, products_without_inventory)
        """
        from app.models.product_inventory import ProductInventory
        
        # Get all active products
        products = self.db.query(Product).filter(
            Product.status == "ACTIVE",
            Product.deleted_at.is_(None)
        ).all()
        
        if not products:
            logger.info("No active products found to sync")
            return {
                "synced_count": 0,
                "total_products": 0,
                "products_without_inventory": 0
            }
        
        product_ids = [p.id for p in products]
        logger.info(f"Syncing inventory for {len(product_ids)} active products...")
        
        # Fetch all inventory in batch
        inventory_client = get_inventory_client()
        inventory_map = await inventory_client.get_stock_batch(product_ids)
        
        # Upsert into product_inventory table
        synced_count = 0
        for product_id, inventory in inventory_map.items():
            if inventory:
                # Use merge to handle both insert and update
                self.db.merge(ProductInventory(
                    product_id=product_id,
                    available_quantity=inventory.get("available_quantity", 0),
                    total_quantity=inventory.get("total_quantity", 0),
                    reserved_quantity=inventory.get("reserved_quantity", 0),
                    allocated_quantity=inventory.get("allocated_quantity", 0),
                ))
                synced_count += 1
        
        self.db.commit()
        products_without_inventory = len(product_ids) - synced_count
        
        logger.info(f"Successfully synced inventory for {synced_count} out of {len(product_ids)} products")
        if products_without_inventory > 0:
            logger.info(f"Note: {products_without_inventory} products have no inventory (this is normal)")
        
        return {
            "synced_count": synced_count,
            "total_products": len(product_ids),
            "products_without_inventory": products_without_inventory
        }

