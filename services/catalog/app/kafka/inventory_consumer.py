"""Kafka consumer for inventory events from inventory service"""
import json
import logging
from typing import Dict, Any, Optional
from uuid import UUID
from confluent_kafka import Consumer, KafkaError
from app.config import settings
from app.db.database import SessionLocal
from app.models.product_inventory import ProductInventory

logger = logging.getLogger(__name__)


class InventoryEventConsumer:
    """Consumes inventory events from Kafka and updates denormalized product_inventory table"""
    
    def __init__(self):
        self.consumer = None
        self.running = False
        
    def _create_consumer(self) -> Consumer:
        """Create and configure Kafka consumer"""
        return Consumer({
            'bootstrap.servers': settings.kafka_bootstrap_servers,
            'group.id': 'catalog-service-inventory-consumer',
            'auto.offset.reset': 'earliest',  # Start from beginning if no offset
            'enable.auto.commit': False,  # Manual commit for better control
            'session.timeout.ms': 30000,
            'max.poll.interval.ms': 300000,
        })
    
    def start(self):
        """Start consuming inventory events"""
        if self.running:
            logger.warning("Inventory event consumer is already running")
            return
        
        try:
            self.consumer = self._create_consumer()
            self.consumer.subscribe([settings.kafka_inventory_events_topic])
            self.running = True
            
            logger.info(f"Started inventory event consumer for topic: {settings.kafka_inventory_events_topic}")
            
            while self.running:
                msg = self.consumer.poll(timeout=1.0)
                if msg is None:
                    continue
                    
                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        # End of partition - this is normal, continue
                        continue
                    else:
                        logger.error(f"Consumer error: {msg.error()}")
                        continue
                
                try:
                    # Parse event - payload is the event object itself
                    event_data = json.loads(msg.value().decode('utf-8'))
                    
                    # Get event type from headers (inventory service sets this)
                    event_type = None
                    if msg.headers():
                        for header in msg.headers():
                            if header[0] == 'type':
                                event_type = header[1].decode('utf-8') if isinstance(header[1], bytes) else header[1]
                                break
                    
                    if not event_type:
                        logger.warning(f"Event missing type header, skipping: {msg.key()}")
                        self.consumer.commit(msg)
                        continue
                    
                    # Process event
                    self._process_event(event_type, event_data)
                    
                    # Commit message after successful processing
                    self.consumer.commit(msg)
                    
                except json.JSONDecodeError as e:
                    logger.error(f"Failed to parse event JSON: {e}")
                    # Commit anyway to avoid reprocessing bad messages
                    self.consumer.commit(msg)
                except Exception as e:
                    logger.error(f"Error processing inventory event: {e}", exc_info=True)
                    # Don't commit on error - allow retry
                    
        except Exception as e:
            if "Kafka" in str(type(e).__name__):
                logger.error(f"Kafka error in inventory consumer: {e}", exc_info=True)
            else:
                raise
        except KeyboardInterrupt:
            logger.info("Stopping inventory event consumer (KeyboardInterrupt)")
        except Exception as e:
            logger.error(f"Unexpected error in inventory consumer: {e}", exc_info=True)
        finally:
            self.stop()
    
    def stop(self):
        """Stop consuming events"""
        self.running = False
        if self.consumer:
            self.consumer.close()
            logger.info("Stopped inventory event consumer")
    
    def _process_event(self, event_type: str, event_data: Dict[str, Any]):
        """Process inventory event and update denormalized table"""
        try:
            # Extract product_id from event
            product_id_str = event_data.get('productId')
            if not product_id_str:
                logger.warning(f"Event missing productId: {event_type}")
                return
            
            product_id = UUID(product_id_str)
            db = SessionLocal()
            
            try:
                if event_type == 'StockCreatedEvent':
                    self._handle_stock_created(db, product_id, event_data)
                elif event_type == 'StockUpdatedEvent':
                    self._handle_stock_updated(db, product_id, event_data)
                else:
                    logger.debug(f"Ignoring event type: {event_type}")
                    
            except Exception as e:
                db.rollback()
                logger.error(f"Error updating inventory cache for product {product_id}: {e}", exc_info=True)
                raise
            finally:
                db.close()
                
        except ValueError as e:
            logger.error(f"Invalid UUID in event: {e}")
        except Exception as e:
            logger.error(f"Error processing event {event_type}: {e}", exc_info=True)
            raise
    
    def _handle_stock_created(self, db, product_id: UUID, event_data: Dict[str, Any]):
        """Handle StockCreatedEvent - create new inventory cache entry"""
        inventory = ProductInventory(
            product_id=product_id,
            available_quantity=event_data.get('availableQuantity', 0),
            total_quantity=event_data.get('totalQuantity', 0),
            reserved_quantity=event_data.get('reservedQuantity', 0),
            allocated_quantity=event_data.get('allocatedQuantity', 0)
        )
        db.add(inventory)
        db.commit()
        logger.info(f"Created inventory cache for product {product_id}")
    
    def _handle_stock_updated(self, db, product_id: UUID, event_data: Dict[str, Any]):
        """Handle StockUpdatedEvent - update existing inventory cache entry"""
        inventory = db.query(ProductInventory).filter(
            ProductInventory.product_id == product_id
        ).first()
        
        if inventory:
            # Update existing record
            inventory.available_quantity = event_data.get('availableQuantity', inventory.available_quantity)
            inventory.total_quantity = event_data.get('totalQuantity', inventory.total_quantity)
            inventory.reserved_quantity = event_data.get('reservedQuantity', inventory.reserved_quantity)
            inventory.allocated_quantity = event_data.get('allocatedQuantity', inventory.allocated_quantity)
            db.commit()
            logger.debug(f"Updated inventory cache for product {product_id}")
        else:
            # Create if doesn't exist (handles race conditions or missed events)
            inventory = ProductInventory(
                product_id=product_id,
                available_quantity=event_data.get('availableQuantity', 0),
                total_quantity=event_data.get('totalQuantity', 0),
                reserved_quantity=event_data.get('reservedQuantity', 0),
                allocated_quantity=event_data.get('allocatedQuantity', 0)
            )
            db.add(inventory)
            db.commit()
            logger.info(f"Created inventory cache for product {product_id} (from update event)")


# Global consumer instance
_inventory_consumer: Optional[InventoryEventConsumer] = None


def get_inventory_consumer() -> InventoryEventConsumer:
    """Get the inventory event consumer singleton"""
    global _inventory_consumer
    if _inventory_consumer is None:
        _inventory_consumer = InventoryEventConsumer()
    return _inventory_consumer
