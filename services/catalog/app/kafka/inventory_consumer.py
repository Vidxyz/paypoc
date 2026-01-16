"""Kafka consumer for inventory events from inventory service"""
import json
import logging
import sys
from typing import Dict, Any, Optional
from uuid import UUID
from confluent_kafka import Consumer, KafkaError
from app.config import settings
from app.db.database import SessionLocal
from app.models.product_inventory import ProductInventory

# Custom StreamHandler that flushes after each emit
class FlushingStreamHandler(logging.StreamHandler):
    def emit(self, record):
        super().emit(record)
        self.flush()

# Configure logger to output to stdout with immediate flushing
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

# Ensure logger has a handler to stdout that flushes after each log
if not logger.handlers:
    handler = FlushingStreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    logger.addHandler(handler)
logger.propagate = True


class InventoryEventConsumer:
    """Consumes inventory events from Kafka and updates denormalized product_inventory table"""
    
    def __init__(self):
        self.consumer = None
        self.running = False
        
    def _create_consumer(self) -> Consumer:
        """Create and configure Kafka consumer
        
        Note: confluent_kafka returns bytes by default, which we decode manually.
        Spring Kafka serializes JSON to bytes, so we decode and parse as JSON.
        """
        config = {
            'bootstrap.servers': settings.kafka_bootstrap_servers,
            'group.id': 'catalog-service-inventory-consumer',
            'auto.offset.reset': 'earliest',  # Start from beginning if no committed offset
            'enable.auto.commit': False,  # Manual commit for better control
            'session.timeout.ms': 30000,
            'max.poll.interval.ms': 300000,
        }
        logger.info(f"Creating Kafka consumer with config: bootstrap_servers={config['bootstrap.servers']}, group_id={config['group.id']}, topic={settings.kafka_inventory_events_topic}")
        logger.info(f"Consumer will start from earliest available offset if no offset is committed for this consumer group")
        return Consumer(config)
    
    def start(self):
        """Start consuming inventory events"""
        print("KAFKA CONSUMER: start() method called", flush=True)
        
        if self.running:
            print("KAFKA CONSUMER: Already running, returning", flush=True)
            logger.warning("KAFKA CONSUMER: Inventory event consumer is already running")
            return
        
        print("=" * 80, flush=True)
        print("KAFKA INVENTORY CONSUMER: Starting initialization", flush=True)
        print(f"KAFKA CONSUMER: Bootstrap servers: {settings.kafka_bootstrap_servers}", flush=True)
        print(f"KAFKA CONSUMER: Topic: {settings.kafka_inventory_events_topic}", flush=True)
        print(f"KAFKA CONSUMER: Consumer group: catalog-service-inventory-consumer", flush=True)
        print("=" * 80, flush=True)
        
        logger.info("=" * 80)
        logger.info("KAFKA INVENTORY CONSUMER: Starting initialization")
        logger.info(f"KAFKA CONSUMER: Bootstrap servers: {settings.kafka_bootstrap_servers}")
        logger.info(f"KAFKA CONSUMER: Topic: {settings.kafka_inventory_events_topic}")
        logger.info(f"KAFKA CONSUMER: Consumer group: catalog-service-inventory-consumer")
        logger.info("=" * 80)
        
        try:
            print("KAFKA CONSUMER: Creating consumer instance...", flush=True)
            self.consumer = self._create_consumer()
            print("KAFKA CONSUMER: Consumer instance created successfully", flush=True)
            logger.info(f"KAFKA CONSUMER: Consumer instance created successfully")
            
            print(f"KAFKA CONSUMER: Subscribing to topic: {settings.kafka_inventory_events_topic}", flush=True)
            self.consumer.subscribe([settings.kafka_inventory_events_topic])
            self.running = True
            
            print(f"KAFKA CONSUMER: Successfully subscribed to topic: {settings.kafka_inventory_events_topic}", flush=True)
            print("KAFKA CONSUMER: Starting poll loop...", flush=True)
            logger.info(f"KAFKA CONSUMER: Successfully subscribed to topic: {settings.kafka_inventory_events_topic}")
            logger.info(f"KAFKA CONSUMER: Starting poll loop...")
            
            message_count = 0
            poll_count = 0
            last_heartbeat = 0
            last_connection_log = 0
            
            print("KAFKA CONSUMER: Entering poll loop - consumer is active and waiting for messages", flush=True)
            logger.info("KAFKA CONSUMER: Entering poll loop - consumer is active and waiting for messages")
            
            while self.running:
                try:
                    msg = self.consumer.poll(timeout=1.0)
                    poll_count += 1
                    
                    # Log heartbeat every 60 polls (roughly every minute if timeout is 1.0s)
                    if poll_count - last_heartbeat >= 60:
                        logger.info(f"KAFKA CONSUMER: Heartbeat - polled {poll_count} times, processed {message_count} messages")
                        last_heartbeat = poll_count
                    
                    if msg is None:
                        continue
                except Exception as poll_error:
                    logger.error(f"KAFKA CONSUMER: Error during poll: {poll_error}", exc_info=True)
                    continue
                    
                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        # End of partition - this is normal, continue
                        logger.debug(f"KAFKA CONSUMER: Reached end of partition: {msg.partition()}")
                        continue
                    else:
                        logger.error(f"KAFKA CONSUMER ERROR: {msg.error()}")
                        continue
                
                message_count += 1
                key_str = msg.key().decode('utf-8') if msg.key() else 'None'
                print(f"KAFKA CONSUMER: Received message #{message_count} - topic={msg.topic()}, partition={msg.partition()}, offset={msg.offset()}, key={key_str}", flush=True)
                logger.info(f"KAFKA CONSUMER: Received message #{message_count} - topic={msg.topic()}, partition={msg.partition()}, offset={msg.offset()}, key={key_str}")
                sys.stdout.flush()  # Force flush for visibility
                
                try:
                    # Parse event - payload is the event object itself (JSON)
                    raw_value = msg.value().decode('utf-8')
                    logger.debug(f"Raw event payload: {raw_value}")
                    event_data = json.loads(raw_value)
                    logger.debug(f"Parsed event data: {event_data}")
                    
                    # Get event type from payload (consistent with catalog service format)
                    if not isinstance(event_data, dict):
                        logger.warning(f"Event payload is not a dict, skipping message. Key: {msg.key()}")
                        self.consumer.commit(msg)
                        continue
                    
                    event_type = event_data.get('type')
                    if not event_type:
                        logger.warning(f"Event missing type in payload, skipping message. Key: {msg.key()}, Payload keys: {list(event_data.keys())}")
                        self.consumer.commit(msg)
                        continue
                    
                    product_id = event_data.get('productId', 'N/A')
                    print(f"KAFKA CONSUMER: Processing event - type={event_type}, productId={product_id}", flush=True)
                    logger.info(f"KAFKA CONSUMER: Processing event - type={event_type}, productId={product_id}")
                    
                    # Process event
                    self._process_event(event_type, event_data)
                    
                    # Commit message after successful processing
                    self.consumer.commit(msg)
                    print(f"KAFKA CONSUMER: Successfully processed and committed {event_type} for product {product_id}", flush=True)
                    logger.info(f"KAFKA CONSUMER: Successfully processed and committed {event_type} for product {product_id}")
                    
                except json.JSONDecodeError as e:
                    logger.error(f"KAFKA CONSUMER: Failed to parse event JSON: {e}. Raw payload: {msg.value().decode('utf-8') if msg.value() else 'None'}")
                    # Commit anyway to avoid reprocessing bad messages
                    self.consumer.commit(msg)
                except Exception as e:
                    logger.error(f"KAFKA CONSUMER: Error processing inventory event: {e}", exc_info=True)
                    # Don't commit on error - allow retry
                    
        except KeyboardInterrupt:
            logger.info("KAFKA CONSUMER: Stopping due to KeyboardInterrupt")
        except Exception as e:
            error_type = type(e).__name__
            logger.error("=" * 80)
            logger.error(f"KAFKA CONSUMER: Fatal error in consumer loop")
            logger.error(f"KAFKA CONSUMER: Error type: {error_type}")
            logger.error(f"KAFKA CONSUMER: Error message: {e}")
            logger.error("=" * 80, exc_info=True)
            if "Kafka" in error_type or "kafka" in str(e).lower() or "connection" in str(e).lower():
                logger.error(f"KAFKA CONSUMER: Kafka connectivity error detected")
                logger.error(f"KAFKA CONSUMER: Bootstrap servers: {settings.kafka_bootstrap_servers}")
                logger.error(f"KAFKA CONSUMER: Topic: {settings.kafka_inventory_events_topic}")
        finally:
            logger.info("KAFKA CONSUMER: Exiting consumer loop, stopping consumer...")
            self.stop()
    
    def stop(self):
        """Stop consuming events"""
        logger.info("KAFKA CONSUMER: Stopping consumer...")
        self.running = False
        if self.consumer:
            try:
                self.consumer.close()
                logger.info("KAFKA CONSUMER: Consumer closed successfully")
            except Exception as e:
                logger.error(f"KAFKA CONSUMER: Error closing consumer: {e}", exc_info=True)
        logger.info("KAFKA CONSUMER: Consumer stopped")
    
    def _process_event(self, event_type: str, event_data: Dict[str, Any]):
        """Process inventory event and update denormalized table"""
        import sys
        try:
            # Extract product_id from event (Kotlin data classes serialize with camelCase)
            product_id_str = event_data.get('productId')
            if not product_id_str:
                logger.warning(f"KAFKA CONSUMER: Event missing productId: {event_type}. Available keys: {list(event_data.keys())}")
                return
            
            product_id = UUID(product_id_str)
            
            # Extract inventory quantities
            available_qty = event_data.get('availableQuantity', 0)
            total_qty = event_data.get('totalQuantity', 0)
            reserved_qty = event_data.get('reservedQuantity', 0)
            allocated_qty = event_data.get('allocatedQuantity', 0)
            
            print(f"KAFKA CONSUMER: Event quantities - available: {available_qty}, total: {total_qty}, reserved: {reserved_qty}, allocated: {allocated_qty}", flush=True)
            logger.info(f"KAFKA CONSUMER: Event quantities - available: {available_qty}, total: {total_qty}, reserved: {reserved_qty}, allocated: {allocated_qty}")
            
            db = SessionLocal()
            
            try:
                if event_type == 'StockCreatedEvent':
                    self._handle_stock_created(db, product_id, event_data)
                elif event_type == 'StockUpdatedEvent':
                    self._handle_stock_updated(db, product_id, event_data)
                else:
                    logger.warning(f"Unknown event type: {event_type}, ignoring")
                    
            except Exception as e:
                db.rollback()
                logger.error(f"Error updating inventory cache for product {product_id}: {e}", exc_info=True)
                raise
            finally:
                db.close()
                
        except ValueError as e:
            logger.error(f"Invalid UUID in event: {e}. productId value: {event_data.get('productId')}")
        except Exception as e:
            logger.error(f"Error processing event {event_type}: {e}", exc_info=True)
            raise
    
    def _handle_stock_created(self, db, product_id: UUID, event_data: Dict[str, Any]):
        """Handle StockCreatedEvent - create new inventory cache entry"""
        # Check if already exists (race condition handling)
        existing = db.query(ProductInventory).filter(
            ProductInventory.product_id == product_id
        ).first()
        
        if existing:
            logger.debug(f"Inventory cache already exists for product {product_id}, updating instead of creating")
            old_available = existing.available_quantity
            old_total = existing.total_quantity
            existing.available_quantity = event_data.get('availableQuantity', 0)
            existing.total_quantity = event_data.get('totalQuantity', 0)
            existing.reserved_quantity = event_data.get('reservedQuantity', 0)
            existing.allocated_quantity = event_data.get('allocatedQuantity', 0)
            db.commit()
            print(f"KAFKA CONSUMER: Updated inventory cache for product {product_id}: available {old_available}->{existing.available_quantity}, total {old_total}->{existing.total_quantity}", flush=True)
            logger.info(f"KAFKA CONSUMER: Updated inventory cache for product {product_id}: available {old_available}->{existing.available_quantity}, total {old_total}->{existing.total_quantity}")
        else:
            inventory = ProductInventory(
                product_id=product_id,
                available_quantity=event_data.get('availableQuantity', 0),
                total_quantity=event_data.get('totalQuantity', 0),
                reserved_quantity=event_data.get('reservedQuantity', 0),
                allocated_quantity=event_data.get('allocatedQuantity', 0)
            )
            db.add(inventory)
            db.commit()
            print(f"KAFKA CONSUMER: Created inventory cache for product {product_id}: available={inventory.available_quantity}, total={inventory.total_quantity}", flush=True)
            logger.info(f"KAFKA CONSUMER: Created inventory cache for product {product_id}: available={inventory.available_quantity}, total={inventory.total_quantity}")
    
    def _handle_stock_updated(self, db, product_id: UUID, event_data: Dict[str, Any]):
        """Handle StockUpdatedEvent - update existing inventory cache entry"""
        inventory = db.query(ProductInventory).filter(
            ProductInventory.product_id == product_id
        ).first()
        
        available_qty = event_data.get('availableQuantity', 0)
        total_qty = event_data.get('totalQuantity', 0)
        reserved_qty = event_data.get('reservedQuantity', 0)
        allocated_qty = event_data.get('allocatedQuantity', 0)
        
        if inventory:
            # Update existing record
            old_available = inventory.available_quantity
            old_total = inventory.total_quantity
            inventory.available_quantity = available_qty
            inventory.total_quantity = total_qty
            inventory.reserved_quantity = reserved_qty
            inventory.allocated_quantity = allocated_qty
            db.commit()
            print(f"KAFKA CONSUMER: Updated inventory cache for product {product_id}: available {old_available}->{available_qty}, total {old_total}->{total_qty}", flush=True)
            logger.info(f"KAFKA CONSUMER: Updated inventory cache for product {product_id}: available {old_available}->{available_qty}, total {old_total}->{total_qty}")
        else:
            # Create if doesn't exist (handles race conditions or missed events)
            inventory = ProductInventory(
                product_id=product_id,
                available_quantity=available_qty,
                total_quantity=total_qty,
                reserved_quantity=reserved_qty,
                allocated_quantity=allocated_qty
            )
            db.add(inventory)
            db.commit()
            print(f"KAFKA CONSUMER: Created inventory cache for product {product_id} (from update event): available={available_qty}, total={total_qty}", flush=True)
            logger.info(f"KAFKA CONSUMER: Created inventory cache for product {product_id} (from update event): available={available_qty}, total={total_qty}")


# Global consumer instance
_inventory_consumer: Optional[InventoryEventConsumer] = None


def get_inventory_consumer() -> InventoryEventConsumer:
    """Get the inventory event consumer singleton"""
    global _inventory_consumer
    if _inventory_consumer is None:
        _inventory_consumer = InventoryEventConsumer()
    return _inventory_consumer
