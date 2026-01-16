"""Client for communicating with the Inventory Service"""
import httpx
import logging
from typing import Optional, Dict, List
from uuid import UUID
from app.config import settings

logger = logging.getLogger(__name__)


class InventoryClient:
    """Client for fetching inventory information from Inventory Service"""
    
    def __init__(self):
        self.base_url = settings.inventory_service_url.rstrip('/')
        self.internal_token = settings.inventory_internal_api_token
    
    def _get_headers(self) -> Dict[str, str]:
        """Get headers for inventory service requests"""
        headers = {"Content-Type": "application/json"}
        if self.internal_token:
            headers["Authorization"] = f"Bearer {self.internal_token}"
        return headers
    
    async def get_stock_by_product_id(self, product_id: UUID, auth_token: Optional[str] = None) -> Optional[Dict]:
        """Get stock information for a product by product ID
        
        Args:
            product_id: Product UUID
            auth_token: Not used (kept for backward compatibility)
            
        Returns:
            Inventory information dict or None if not found
        """
        try:
            headers = self._get_headers()
            
            async with httpx.AsyncClient(timeout=5.0) as client:
                response = await client.get(
                    f"{self.base_url}/api/inventory/stock/{product_id}",
                    headers=headers
                )
                
                if response.status_code == 200:
                    data = response.json()
                    # Map inventory service response to our schema
                    return {
                        "inventory_id": UUID(data.get("id")),
                        "available_quantity": data.get("availableQuantity", 0),
                        "reserved_quantity": data.get("reservedQuantity", 0),
                        "allocated_quantity": data.get("allocatedQuantity", 0),
                        "total_quantity": data.get("totalQuantity", 0),
                        "low_stock_threshold": data.get("lowStockThreshold"),
                    }
                elif response.status_code == 404:
                    # Product doesn't have inventory yet - this is normal
                    return None
                else:
                    logger.warning(f"Inventory service returned {response.status_code} for product {product_id}")
                    return None
        except Exception as e:
            logger.warning(f"Failed to fetch inventory for product {product_id}: {e}")
            return None
    
    async def get_stock_batch(self, product_ids: List[UUID], auth_token: Optional[str] = None) -> Dict[UUID, Optional[Dict]]:
        """Get stock information for multiple products in batch using the internal batch API
        
        Args:
            product_ids: List of product UUIDs
            auth_token: Not used (kept for backward compatibility)
            
        Returns:
            Dictionary mapping product_id to inventory info (or None if not found)
        """
        if not product_ids:
            return {}
        
        try:
            headers = self._get_headers()
            
            # Prepare request body with product IDs
            request_body = {
                "productIds": [str(pid) for pid in product_ids]
            }
            
            async with httpx.AsyncClient(timeout=10.0) as client:
                response = await client.post(
                    f"{self.base_url}/internal/stock/batch",
                    headers=headers,
                    json=request_body
                )
                
                if response.status_code == 200:
                    # Response is a map of productId (string) -> inventory dict
                    batch_response = response.json()
                    
                    # Convert to our format: UUID -> inventory dict
                    inventory_map = {}
                    for product_id in product_ids:
                        product_id_str = str(product_id)
                        if product_id_str in batch_response:
                            inventory_data = batch_response[product_id_str]
                            # Map inventory service response to our schema
                            inventory_map[product_id] = {
                                "inventory_id": UUID(inventory_data.get("id")),
                                "available_quantity": inventory_data.get("availableQuantity", 0),
                                "reserved_quantity": inventory_data.get("reservedQuantity", 0),
                                "allocated_quantity": inventory_data.get("allocatedQuantity", 0),
                                "total_quantity": inventory_data.get("totalQuantity", 0),
                                "low_stock_threshold": inventory_data.get("lowStockThreshold"),
                            }
                        else:
                            # Product doesn't have inventory - this is normal
                            inventory_map[product_id] = None
                    
                    return inventory_map
                else:
                    logger.warning(f"Inventory service batch API returned {response.status_code}: {response.text}")
                    # Return empty map - all products will have no inventory
                    return {pid: None for pid in product_ids}
        except Exception as e:
            logger.error(f"Failed to fetch inventory batch for products: {e}", exc_info=True)
            # Return empty map - all products will have no inventory
            return {pid: None for pid in product_ids}


# Singleton instance
_inventory_client: Optional[InventoryClient] = None


def get_inventory_client() -> InventoryClient:
    """Get the inventory client singleton"""
    global _inventory_client
    if _inventory_client is None:
        _inventory_client = InventoryClient()
    return _inventory_client

