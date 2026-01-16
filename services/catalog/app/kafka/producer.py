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
        
        logger.info("KAFKA PRODUCER: Initializing producer...")
        logger.info(f"KAFKA PRODUCER: Bootstrap servers: {self.bootstrap_servers}")
        logger.info(f"KAFKA PRODUCER: Events topic: {self.events_topic}")
        
        # Create Kafka producer
        try:
            self.producer = Producer({
                'bootstrap.servers': self.bootstrap_servers,
                'client.id': 'catalog-service',
            })
            logger.info("KAFKA PRODUCER: Producer instance created successfully")
        except Exception as e:
            logger.error(f"KAFKA PRODUCER: Failed to create producer instance: {e}", exc_info=True)
            raise
    
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
            
            logger.info(f"KAFKA PRODUCER: Published {event_type} event to topic={self.events_topic}, key={kafka_key}")
        except Exception as e:
            logger.error(f"KAFKA PRODUCER: Failed to publish {event_type} event: {e}", exc_info=True)
            raise
    
    def _delivery_callback(self, err, msg):
        """Callback for message delivery"""
        if err:
            logger.error(f"KAFKA PRODUCER: Message delivery failed - topic={msg.topic() if msg else 'N/A'}, error={err}")
        else:
            logger.info(f"KAFKA PRODUCER: Message delivered successfully - topic={msg.topic()}, partition={msg.partition()}, offset={msg.offset()}")
    
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
        try:
            logger.info("KAFKA PRODUCER: Flushing pending messages...")
            self.producer.flush()
            logger.info("KAFKA PRODUCER: Flush completed")
        except Exception as e:
            logger.error(f"KAFKA PRODUCER: Error during flush: {e}", exc_info=True)
            raise


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
            logger.info("KAFKA PRODUCER: Creating new producer instance (lazy initialization)...")
            _event_producer_instance = CatalogEventProducer()
            logger.info(f"KAFKA PRODUCER: Successfully initialized producer for bootstrap_servers={_event_producer_instance.bootstrap_servers}, topic={_event_producer_instance.events_topic}")
        except Exception as e:
            logger.error(f"KAFKA PRODUCER: Failed to initialize producer: {e}. Events will not be published.", exc_info=True)
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
                logger.info(f"KAFKA PRODUCER: Attempting to publish PRODUCT_CREATED event with args={args}, kwargs={kwargs}")
                producer.publish_product_created(*args, **kwargs)
            except Exception as e:
                logger.error(f"KAFKA PRODUCER: Failed to publish PRODUCT_CREATED event: {e}", exc_info=True)
        else:
            logger.warning("KAFKA PRODUCER: Producer unavailable, skipping PRODUCT_CREATED event")
    
    def publish_product_updated(self, *args, **kwargs):
        producer = get_event_producer()
        if producer:
            try:
                logger.info(f"KAFKA PRODUCER: Attempting to publish PRODUCT_UPDATED event with args={args}, kwargs={kwargs}")
                producer.publish_product_updated(*args, **kwargs)
            except Exception as e:
                logger.error(f"KAFKA PRODUCER: Failed to publish PRODUCT_UPDATED event: {e}", exc_info=True)
        else:
            logger.warning("KAFKA PRODUCER: Producer unavailable, skipping PRODUCT_UPDATED event")
    
    def publish_product_deleted(self, *args, **kwargs):
        producer = get_event_producer()
        if producer:
            try:
                logger.info(f"KAFKA PRODUCER: Attempting to publish PRODUCT_DELETED event with args={args}, kwargs={kwargs}")
                producer.publish_product_deleted(*args, **kwargs)
            except Exception as e:
                logger.error(f"KAFKA PRODUCER: Failed to publish PRODUCT_DELETED event: {e}", exc_info=True)
        else:
            logger.warning("KAFKA PRODUCER: Producer unavailable, skipping PRODUCT_DELETED event")
    
    def flush(self):
        producer = get_event_producer()
        if producer:
            try:
                logger.info("KAFKA PRODUCER: Flushing producer via proxy...")
                producer.flush()
            except Exception as e:
                logger.error(f"KAFKA PRODUCER: Failed to flush producer: {e}", exc_info=True)
        else:
            logger.warning("KAFKA PRODUCER: Producer unavailable, cannot flush")

event_producer = EventProducerProxy()

