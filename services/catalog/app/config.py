from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field
from typing import Optional


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        case_sensitive=False,
        extra="ignore"
    )
    
    # Application
    app_name: str = "catalog-service"
    debug: bool = False
    
    # Database
    database_url: str = Field(
        default="postgresql://catalog_user:catalog_password@localhost:5432/catalog_db",
        validation_alias="DATABASE_URL"
    )
    
    # Auth0
    auth0_domain: str = Field(..., validation_alias="AUTH0_DOMAIN")
    auth0_audience: Optional[str] = Field(None, validation_alias="AUTH0_AUDIENCE")
    
    # Kafka
    kafka_bootstrap_servers: str = Field(
        default="localhost:9092",
        validation_alias="KAFKA_BOOTSTRAP_SERVERS"
    )
    kafka_events_topic: str = Field(
        default="catalog.events",
        validation_alias="KAFKA_EVENTS_TOPIC"
    )
    kafka_inventory_events_topic: str = Field(
        default="inventory.events",
        validation_alias="KAFKA_INVENTORY_EVENTS_TOPIC"
    )
    
    # Cloudinary (active image provider)
    cloudinary_cloud_name: Optional[str] = Field(None, validation_alias="CLOUDINARY_CLOUD_NAME")
    cloudinary_api_key: Optional[str] = Field(None, validation_alias="CLOUDINARY_API_KEY")
    cloudinary_api_secret: Optional[str] = Field(None, validation_alias="CLOUDINARY_API_SECRET")
    
    # CloudFlare Images (unused - kept for future use)
    cloudflare_account_id: Optional[str] = Field(None, validation_alias="CLOUDFLARE_ACCOUNT_ID")
    cloudflare_api_token: Optional[str] = Field(None, validation_alias="CLOUDFLARE_API_TOKEN")
    
    # Payments Service - not used (sellerId = email, no API call needed)
    # payments_service_url: str = "http://localhost:8080"  # Removed - unused
    # payments_internal_api_token: Optional[str] = None  # Removed - unused
    
    # Inventory Service (for stock information)
    inventory_service_url: str = Field(
        default="http://localhost:8083",
        validation_alias="INVENTORY_SERVICE_URL"
    )
    inventory_internal_api_token: Optional[str] = Field(
        None,
        validation_alias="INVENTORY_INTERNAL_API_TOKEN"
    )


settings = Settings()

