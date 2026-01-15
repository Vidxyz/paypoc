from sqlalchemy.orm import Session
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
        
        total = query.count()
        
        # For availability sorting, fetch a larger set to sort properly, then paginate
        # For newest sorting, fetch only the needed page (more efficient)
        if sort_by == "availability":
            # Fetch up to 10 pages worth to sort by availability, then paginate
            # This is a trade-off: better sorting accuracy vs. performance
            fetch_limit = min(page_size * 10, total)  # Fetch up to 10 pages or all products, whichever is less
            products_to_sort = query.order_by(Product.created_at.desc()).limit(fetch_limit).all()
        else:
            # Default: newest first - fetch only the page we need
            products_to_sort = query.order_by(Product.created_at.desc()).offset(
                (page - 1) * page_size
            ).limit(page_size).all()
        
        # Fetch inventory for products in batch (using internal API token)
        inventory_map = {}
        if products_to_sort:
            try:
                inventory_client = get_inventory_client()
                product_ids = [p.id for p in products_to_sort]
                logger.debug(f"Fetching inventory for {len(product_ids)} products in browse: {product_ids}")
                inventory_map = await inventory_client.get_stock_batch(product_ids)
                inventory_count = len([v for v in inventory_map.values() if v is not None])
                logger.info(f"Successfully fetched inventory for {inventory_count} out of {len(product_ids)} products in browse")
            except Exception as e:
                logger.error(f"Failed to fetch inventory information in browse: {e}", exc_info=True)
                # Continue without inventory - it's optional
        
        # Sort by availability if requested
        # todo-vh: This isnt very efficient - we might need an API from inventory service to fetch this more efficinetly instead of stitching response here
        if sort_by == "availability":
            def get_available_quantity(product):
                inventory = inventory_map.get(product.id)
                if inventory is None:
                    return 0
                return inventory.get("available_quantity", inventory.get("availableQuantity", 0))
            
            # Sort: in-stock first (available_quantity > 0), then by quantity descending, then by creation date
            products_to_sort.sort(key=lambda p: (
                0 if get_available_quantity(p) > 0 else 1,  # In stock first
                -get_available_quantity(p),  # Higher quantity first
                -p.created_at.timestamp() if p.created_at else 0  # Newer first as tiebreaker
            ))
            
            # Apply pagination after sorting
            paginated_products = products_to_sort[(page - 1) * page_size:page * page_size]
        else:
            # Already paginated for newest sorting
            paginated_products = products_to_sort
        
        # Convert products to response dicts with image URLs and inventory
        product_dicts = []
        for product in paginated_products:
            inventory = inventory_map.get(product.id)
            product_dicts.append(self._product_to_response_dict(product, inventory))
        
        return ProductListResponse(
            products=product_dicts,
            total=total,
            page=page,
            page_size=page_size,
            has_next=(page * page_size) < total
        )

