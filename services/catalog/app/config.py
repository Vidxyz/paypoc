from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    # Application
    app_name: str = "catalog-service"
    debug: bool = False
    
    # Database
    database_url: str = "postgresql://catalog_user:catalog_password@localhost:5432/catalog_db"
    
    # Auth0
    auth0_domain: str
    auth0_audience: Optional[str] = None
    
    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_events_topic: str = "catalog.events"
    kafka_inventory_events_topic: str = "inventory.events"  # Topic from inventory service
    
    # Cloudinary (active image provider)
    cloudinary_cloud_name: Optional[str] = None
    cloudinary_api_key: Optional[str] = None
    cloudinary_api_secret: Optional[str] = None
    
    # CloudFlare Images (unused - kept for future use)
    cloudflare_account_id: Optional[str] = None
    cloudflare_api_token: Optional[str] = None
    
    # Payments Service (for seller ID resolution)
    payments_service_url: str = "http://localhost:8080"
    payments_internal_api_token: Optional[str] = None
    
    # Inventory Service (for stock information)
    inventory_service_url: str = "http://localhost:8083"
    inventory_internal_api_token: Optional[str] = None
    
    class Config:
        env_file = ".env"
        case_sensitive = False


settings = Settings()

