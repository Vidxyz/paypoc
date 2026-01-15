"""
Kafka producer for catalog events
"""
import json
import logging
import uuid
from datetime import datetime
from typing import Dict, Any, Optional
from confluent_kafka import Producer
from app.config import settings

logger = logging.getLogger(__name__)


class CatalogEventProducer:
    """Kafka producer for catalog events"""
    
    def __init__(self):
        self.bootstrap_servers = settings.kafka_bootstrap_servers
        self.events_topic = settings.kafka_events_topic
        
        # Create Kafka producer
        self.producer = Producer({
            'bootstrap.servers': self.bootstrap_servers,
            'client.id': 'catalog-service',
        })
    
    def _publish_event(self, event_type: str, payload: Dict[str, Any], key: Optional[str] = None):
        """Internal method to publish event to Kafka"""
        event = {
            "type": event_type,
            "eventId": str(uuid.uuid4()),
            "createdAt": datetime.utcnow().isoformat() + "Z",
            **payload
        }
        
        try:
            # Use key for partitioning (product ID, category ID, etc.)
            kafka_key = key or event.get("productId") or event.get("categoryId") or str(uuid.uuid4())
            
            self.producer.produce(
                self.events_topic,
                key=kafka_key,
                value=json.dumps(event).encode('utf-8'),
                callback=self._delivery_callback
            )
            
            # Trigger delivery callback
            self.producer.poll(0)
            
            logger.info(f"Published {event_type} event to {self.events_topic}")
        except Exception as e:
            logger.error(f"Failed to publish {event_type} event: {e}", exc_info=True)
            raise
    
    def _delivery_callback(self, err, msg):
        """Callback for message delivery"""
        if err:
            logger.error(f"Message delivery failed: {err}")
        else:
            logger.debug(f"Message delivered to {msg.topic()} [{msg.partition()}]")
    
    def publish_product_created(self, product_id: str, seller_id: str, sku: str, name: str):
        """Publish ProductCreatedEvent"""
        self._publish_event(
            "PRODUCT_CREATED",
            {
                "productId": product_id,
                "sellerId": seller_id,
                "sku": sku,
                "name": name
            },
            key=product_id
        )
    
    def publish_product_updated(self, product_id: str, seller_id: str, sku: str, name: str):
        """Publish ProductUpdatedEvent"""
        self._publish_event(
            "PRODUCT_UPDATED",
            {
                "productId": product_id,
                "sellerId": seller_id,
                "sku": sku,
                "name": name
            },
            key=product_id
        )
    
    def publish_product_deleted(self, product_id: str, seller_id: str, sku: str):
        """Publish ProductDeletedEvent"""
        self._publish_event(
            "PRODUCT_DELETED",
            {
                "productId": product_id,
                "sellerId": seller_id,
                "sku": sku
            },
            key=product_id
        )
    
    def flush(self):
        """Flush pending messages"""
        self.producer.flush()


# Lazy initialization - only create producer when first used
_event_producer_instance = None
_producer_initialization_failed = False

class NoOpEventProducer:
    """No-op producer that does nothing when Kafka is unavailable"""
    def publish_product_created(self, *args, **kwargs):
        pass  # Silently skip when Kafka is unavailable
    
    def publish_product_updated(self, *args, **kwargs):
        pass
    
    def publish_product_deleted(self, *args, **kwargs):
        pass
    
    def flush(self):
        pass

def get_event_producer():
    """Get or create the global event producer instance (lazy initialization)"""
    global _event_producer_instance, _producer_initialization_failed
    
    if _producer_initialization_failed:
        return None
    
    if _event_producer_instance is None:
        try:
            _event_producer_instance = CatalogEventProducer()
            logger.info(f"Initialized Kafka producer for {_event_producer_instance.bootstrap_servers}")
        except Exception as e:
            logger.warning(f"Failed to initialize Kafka producer: {e}. Events will not be published.")
            _producer_initialization_failed = True
            return None
    return _event_producer_instance

# For backward compatibility, provide a property-like accessor
class EventProducerProxy:
    """Proxy for lazy Kafka producer initialization"""
    def publish_product_created(self, *args, **kwargs):
        producer = get_event_producer()
        if producer:
            try:
                producer.publish_product_created(*args, **kwargs)
            except Exception as e:
                logger.warning(f"Failed to publish PRODUCT_CREATED event: {e}")
        # Silently skip if producer unavailable
    
    def publish_product_updated(self, *args, **kwargs):
        producer = get_event_producer()
        if producer:
            try:
                producer.publish_product_updated(*args, **kwargs)
            except Exception as e:
                logger.warning(f"Failed to publish PRODUCT_UPDATED event: {e}")
    
    def publish_product_deleted(self, *args, **kwargs):
        producer = get_event_producer()
        if producer:
            try:
                producer.publish_product_deleted(*args, **kwargs)
            except Exception as e:
                logger.warning(f"Failed to publish PRODUCT_DELETED event: {e}")
    
    def flush(self):
        producer = get_event_producer()
        if producer:
            try:
                producer.flush()
            except Exception as e:
                logger.warning(f"Failed to flush Kafka producer: {e}")

event_producer = EventProducerProxy()

