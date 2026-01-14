"""
Abstract base class for image providers
"""
from abc import ABC, abstractmethod
from typing import Optional, List


class ImageProvider(ABC):
    """Abstract interface for image storage providers"""
    
    @abstractmethod
    def upload_image(self, image_data: bytes, metadata: Optional[dict] = None) -> Optional[str]:
        """
        Upload an image to the provider.
        
        Args:
            image_data: Image bytes
            metadata: Optional metadata (tags, folder, etc.)
        
        Returns:
            Image ID or URL if successful, None otherwise
        """
        pass
    
    @abstractmethod
    def delete_image(self, image_id: str) -> bool:
        """
        Delete an image from the provider.
        
        Args:
            image_id: Image ID or public ID
        
        Returns:
            True if successful, False otherwise
        """
        pass
    
    @abstractmethod
    def get_image_url(self, image_id: str, variant: Optional[str] = None) -> str:
        """
        Get CDN URL for an image.
        
        Args:
            image_id: Image ID or public ID
            variant: Optional variant/transformation name
        
        Returns:
            Full CDN URL for the image
        """
        pass
    
    @abstractmethod
    def upload_multiple(self, image_data_list: List[bytes], metadata: Optional[dict] = None) -> List[Optional[str]]:
        """
        Upload multiple images.
        
        Args:
            image_data_list: List of image bytes
            metadata: Optional metadata applied to all images
        
        Returns:
            List of image IDs (None for failed uploads)
        """
        pass

