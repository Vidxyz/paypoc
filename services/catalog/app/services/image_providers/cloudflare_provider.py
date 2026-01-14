"""
CloudFlare Images provider implementation (UNUSED - kept for future use)

This implementation is not currently used but is kept in the codebase
for potential future use if we need to switch to CloudFlare Images.
"""
import requests
import logging
from typing import Optional, List
from app.services.image_providers.base import ImageProvider
from app.config import settings

logger = logging.getLogger(__name__)


class CloudFlareImageProvider(ImageProvider):
    """
    CloudFlare Images implementation of ImageProvider.
    
    NOTE: This provider is currently UNUSED but kept for potential future use.
    The active provider is CloudinaryImageProvider.
    """
    
    def __init__(self):
        self.account_id = settings.cloudflare_account_id
        self.api_token = settings.cloudflare_api_token
        self.base_url = f"https://api.cloudflare.com/client/v4/accounts/{self.account_id}/images/v1"
        
        self.headers = {
            "Authorization": f"Bearer {self.api_token}"
        }
    
    def upload_image(self, image_data: bytes, metadata: Optional[dict] = None) -> Optional[str]:
        """
        Upload an image to CloudFlare Images.
        Returns the image ID if successful, None otherwise.
        """
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
        """
        Delete an image from CloudFlare Images.
        image_id should be the CloudFlare image ID.
        """
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
        """
        Get CloudFlare CDN URL for an image.
        variant can be 'public' (full-size) or custom variant name.
        """
        if variant == "public":
            return f"https://imagedelivery.net/{self.account_id}/{image_id}/public"
        else:
            return f"https://imagedelivery.net/{self.account_id}/{image_id}/{variant}"
    
    def upload_multiple(self, image_data_list: List[bytes], metadata: Optional[dict] = None) -> List[Optional[str]]:
        """
        Upload multiple images to CloudFlare Images.
        Returns a list of image IDs (None for failed uploads).
        """
        results = []
        for image_data in image_data_list:
            image_id = self.upload_image(image_data, metadata)
            results.append(image_id)
        return results

