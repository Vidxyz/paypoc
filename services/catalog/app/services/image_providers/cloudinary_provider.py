"""
Cloudinary image provider implementation
"""
import cloudinary
import cloudinary.uploader
import cloudinary.api
import logging
from typing import Optional, Dict, List
from app.services.image_providers.base import ImageProvider
from app.config import settings

logger = logging.getLogger(__name__)


class CloudinaryImageProvider(ImageProvider):
    """Cloudinary implementation of ImageProvider"""
    
    def __init__(self):
        self.cloud_name = settings.cloudinary_cloud_name
        self.api_key = settings.cloudinary_api_key
        self.api_secret = settings.cloudinary_api_secret
        
        if not all([self.cloud_name, self.api_key, self.api_secret]):
            logger.warning("Cloudinary not fully configured (missing cloud_name, api_key, or api_secret)")
        else:
            # Configure Cloudinary
            cloudinary.config(
                cloud_name=self.cloud_name,
                api_key=self.api_key,
                api_secret=self.api_secret,
                secure=True  # Use HTTPS
            )
    
    def upload_image(self, image_data: bytes, metadata: Optional[dict] = None) -> Optional[str]:
        """
        Upload an image to Cloudinary.
        Returns the public_id if successful, None otherwise.
        """
        if not all([self.cloud_name, self.api_key, self.api_secret]):
            logger.warning("Cloudinary not configured")
            return None
        
        try:
            upload_options = {}
            if metadata:
                # Common metadata fields
                if "folder" in metadata:
                    upload_options["folder"] = metadata["folder"]
                if "tags" in metadata:
                    upload_options["tags"] = metadata["tags"]
                if "resource_type" in metadata:
                    upload_options["resource_type"] = metadata["resource_type"]
                else:
                    upload_options["resource_type"] = "image"
            
            # Upload to Cloudinary
            result = cloudinary.uploader.upload(
                image_data,
                **upload_options
            )
            
            public_id = result.get("public_id")
            if public_id:
                logger.info(f"Successfully uploaded image to Cloudinary: {public_id}")
                return public_id
            else:
                logger.error(f"Cloudinary upload succeeded but no public_id returned: {result}")
                return None
                
        except Exception as e:
            logger.error(f"Failed to upload image to Cloudinary: {e}", exc_info=True)
            return None
    
    def delete_image(self, image_id: str) -> bool:
        """
        Delete an image from Cloudinary.
        image_id should be the public_id.
        """
        if not all([self.cloud_name, self.api_key, self.api_secret]):
            logger.warning("Cloudinary not configured")
            return False
        
        try:
            result = cloudinary.uploader.destroy(image_id, resource_type="image")
            
            if result.get("result") == "ok":
                logger.info(f"Successfully deleted image from Cloudinary: {image_id}")
                return True
            else:
                logger.error(f"Cloudinary delete failed: {result}")
                return False
                
        except Exception as e:
            logger.error(f"Failed to delete image from Cloudinary: {e}", exc_info=True)
            return False
    
    def get_image_url(self, image_id: str, variant: Optional[str] = None) -> str:
        """
        Get Cloudinary CDN URL for an image.
        variant can be a transformation name or transformation parameters.
        """
        try:
            if variant:
                # Use named transformation or transformation parameters
                url = cloudinary.CloudinaryImage(image_id).build_url(transformation=variant)
            else:
                # Get default URL
                url = cloudinary.CloudinaryImage(image_id).build_url()
            
            return url
        except Exception as e:
            logger.error(f"Failed to generate Cloudinary URL for {image_id}: {e}")
            # Return a fallback URL (though this shouldn't happen)
            return f"https://res.cloudinary.com/{self.cloud_name}/image/upload/{image_id}"
    
    def upload_multiple(self, image_data_list: List[bytes], metadata: Optional[dict] = None) -> List[Optional[str]]:
        """
        Upload multiple images to Cloudinary.
        Returns a list of public_ids (None for failed uploads).
        """
        results = []
        for image_data in image_data_list:
            public_id = self.upload_image(image_data, metadata)
            results.append(public_id)
        return results

