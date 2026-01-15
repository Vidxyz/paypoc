"""
CloudFlare Images service integration (DEPRECATED - Use image_providers instead)

This file is deprecated and kept for reference only.
Please use the abstracted image provider interface:
- app.services.image_providers.base.ImageProvider
- app.services.image_providers.cloudinary_provider.CloudinaryImageProvider
- app.services.image_providers.cloudflare_provider.CloudFlareImageProvider (unused, kept for future use)
"""
import requests
import logging
from typing import Optional
from app.config import settings

logger = logging.getLogger(__name__)


class CloudFlareImagesService:
    """
    DEPRECATED: Use image_providers interface instead.
    
    Service for CloudFlare Images API integration.
    This class is deprecated - use the abstracted ImageProvider interface instead.
    See app.services.image_providers for the current implementation.
    """
    
    def __init__(self):
        self.account_id = settings.cloudflare_account_id
        self.api_token = settings.cloudflare_api_token
        self.base_url = f"https://api.cloudflare.com/client/v4/accounts/{self.account_id}/images/v1"
        
        self.headers = {
            "Authorization": f"Bearer {self.api_token}"
        }
        logger.warning("CloudFlareImagesService is deprecated. Use ImageProvider interface instead.")
    
    def upload_image(self, image_data: bytes, metadata: Optional[dict] = None) -> Optional[str]:
        """DEPRECATED: Use ImageProvider.upload_image() instead"""
        if not self.account_id or not self.api_token:
            logger.warning("CloudFlare Images not configured (missing account_id or api_token)")
            return None
        
        try:
            files = {
                'file': image_data
            }
            
            data = {}
            if metadata:
                data.update(metadata)
            
            response = requests.post(
                f"{self.base_url}",
                headers=self.headers,
                files=files,
                data=data,
                timeout=30
            )
            response.raise_for_status()
            
            result = response.json()
            if result.get("success") and result.get("result"):
                image_id = result["result"].get("id")
                logger.info(f"Successfully uploaded image to CloudFlare: {image_id}")
                return image_id
            else:
                logger.error(f"CloudFlare Images upload failed: {result}")
                return None
                
        except requests.exceptions.RequestException as e:
            logger.error(f"Failed to upload image to CloudFlare: {e}")
            return None
        except Exception as e:
            logger.error(f"Unexpected error uploading image to CloudFlare: {e}", exc_info=True)
            return None
    
    def delete_image(self, image_id: str) -> bool:
        """DEPRECATED: Use ImageProvider.delete_image() instead"""
        if not self.account_id or not self.api_token:
            logger.warning("CloudFlare Images not configured")
            return False
        
        try:
            response = requests.delete(
                f"{self.base_url}/{image_id}",
                headers=self.headers,
                timeout=10
            )
            response.raise_for_status()
            
            result = response.json()
            if result.get("success"):
                logger.info(f"Successfully deleted image from CloudFlare: {image_id}")
                return True
            else:
                logger.error(f"CloudFlare Images delete failed: {result}")
                return False
                
        except requests.exceptions.RequestException as e:
            logger.error(f"Failed to delete image from CloudFlare: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error deleting image from CloudFlare: {e}", exc_info=True)
            return False
    
    def get_image_url(self, image_id: str, variant: Optional[str] = "public") -> str:
        """DEPRECATED: Use ImageProvider.get_image_url() instead"""
        if variant == "public":
            return f"https://imagedelivery.net/{self.account_id}/{image_id}/public"
        else:
            return f"https://imagedelivery.net/{self.account_id}/{image_id}/{variant}"


# DEPRECATED: Use get_image_provider() from app.services instead
cloudflare_images_service = CloudFlareImagesService()
