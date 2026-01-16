import requests
import logging
from typing import Optional
from app.config import settings

logger = logging.getLogger(__name__)


class SellerService:
    """Service to resolve seller ID (uses email directly as sellerId)"""
    
    def __init__(self):
        # Payments service URL not needed - sellerId = email
        pass
    
    def get_seller_id(self, user_id: Optional[str], email: Optional[str]) -> str:
        """
        Get seller ID from Payments Service.
        According to the design, sellerId = email in payments service.
        So we can use email directly as sellerId.
        """
        if email:
            # sellerId = email in payments service
            return email
        
        if user_id:
            # Fallback: try to fetch from payments service if needed
            # For now, we'll use email as sellerId
            logger.warning(f"No email found for user_id: {user_id}")
            raise ValueError("Email is required for seller ID resolution")
        
        raise ValueError("Either user_id or email is required")

