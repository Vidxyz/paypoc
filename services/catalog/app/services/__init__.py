# Initialize the active image provider (Cloudinary)
_image_provider = None

def get_image_provider():
    """Get the configured image provider instance"""
    global _image_provider
    if _image_provider is None:
        from app.services.image_providers.cloudinary_provider import CloudinaryImageProvider
        _image_provider = CloudinaryImageProvider()
    return _image_provider

