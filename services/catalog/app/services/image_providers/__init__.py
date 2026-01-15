# Package exports - these allow cleaner imports like:
# from app.services.image_providers import ImageProvider, CloudinaryImageProvider
from app.services.image_providers.base import ImageProvider
from app.services.image_providers.cloudinary_provider import CloudinaryImageProvider

