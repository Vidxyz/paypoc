package services

import java.time.Instant
import java.util.UUID
import clients.OrderServiceClient
import models.{Shipment, ShipmentStatus}
import play.api.Logger
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.{Inject, Singleton}

@Singleton
class FulfillmentService @Inject()(
  orderServiceClient: OrderServiceClient,
  eventProducer: FulfillmentEventProducer
)(implicit ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  
  /**
   * Gets a shipment by ID.
   * Validates that the seller owns the shipment.
   */
  def getShipment(shipmentId: UUID, sellerId: String): Future[Option[Shipment]] = {
    orderServiceClient.getShipment(shipmentId).map { shipmentOpt =>
      shipmentOpt.flatMap { shipment =>
        if (shipment.sellerId == sellerId) {
          Some(shipment)
        } else {
          logger.warn(s"Seller $sellerId attempted to access shipment $shipmentId owned by ${shipment.sellerId}")
          None
        }
      }
    }
  }
  
  /**
   * Gets all shipments for a seller.
   */
  def getShipmentsBySeller(sellerId: String, limit: Int = 50): Future[List[Shipment]] = {
    orderServiceClient.getShipmentsBySeller(sellerId, limit, 0)
  }
  
  /**
   * Updates shipment status.
   * Validates seller authorization and state transitions.
   */
  def updateShipmentStatus(
    shipmentId: UUID,
    sellerId: String,
    newStatus: String
  ): Future[Shipment] = {
    // Validate status
    val statusOpt = ShipmentStatus.fromString(newStatus)
    if (statusOpt.isEmpty) {
      return Future.failed(new IllegalArgumentException(s"Invalid shipment status: $newStatus"))
    }
    
    // Get current shipment
    orderServiceClient.getShipment(shipmentId).flatMap {
      case None =>
        Future.failed(new IllegalArgumentException(s"Shipment not found: $shipmentId"))
      case Some(shipment) =>
        // Validate seller authorization
        if (shipment.sellerId != sellerId) {
          logger.warn(s"Seller $sellerId attempted to update shipment $shipmentId owned by ${shipment.sellerId}")
          Future.failed(new SecurityException("Access denied: shipment belongs to different seller"))
        } else {
          val previousStatus = shipment.status
          
          // Validate state transition
          if (!isValidStatusTransition(previousStatus, newStatus)) {
            Future.failed(new IllegalArgumentException(s"Invalid status transition from $previousStatus to $newStatus"))
          } else {
            // Determine timestamps based on status
            val shippedAt = if (newStatus == "SHIPPED" && previousStatus != "SHIPPED") {
              Some(Instant.now())
            } else {
              shipment.shippedAt
            }
            
            val deliveredAt = if (newStatus == "DELIVERED" && previousStatus != "DELIVERED") {
              Some(Instant.now())
            } else {
              shipment.deliveredAt
            }
            
            // Update in Order Service
            orderServiceClient.updateShipmentStatus(shipmentId, newStatus, shippedAt, deliveredAt).flatMap { updatedShipment =>
              // Publish event
              eventProducer.publishShipmentStatusUpdatedEvent(
                shipmentId = shipmentId,
                orderId = shipment.orderId,
                sellerId = sellerId,
                status = newStatus,
                previousStatus = previousStatus
              ).map { _ =>
                // Publish specific events for shipped/delivered
                if (newStatus == "SHIPPED" && previousStatus != "SHIPPED") {
                  updatedShipment.trackingNumber.foreach { trackingNumber =>
                    updatedShipment.carrier.foreach { carrier =>
                      eventProducer.publishShipmentShippedEvent(
                        shipmentId = shipmentId,
                        orderId = shipment.orderId,
                        sellerId = sellerId,
                        trackingNumber = trackingNumber,
                        carrier = carrier,
                        shippedAt = shippedAt.get
                      )
                    }
                  }
                }
                
                if (newStatus == "DELIVERED" && previousStatus != "DELIVERED") {
                  eventProducer.publishShipmentDeliveredEvent(
                    shipmentId = shipmentId,
                    orderId = shipment.orderId,
                    sellerId = sellerId,
                    deliveredAt = deliveredAt.get
                  )
                }
                
                updatedShipment
              }
            }
          }
        }
    }
  }
  
  /**
   * Adds tracking information to a shipment.
   * Validates seller authorization.
   */
  def addTracking(
    shipmentId: UUID,
    sellerId: String,
    trackingNumber: String,
    carrier: String
  ): Future[Shipment] = {
    // Get current shipment
    orderServiceClient.getShipment(shipmentId).flatMap {
      case None =>
        Future.failed(new IllegalArgumentException(s"Shipment not found: $shipmentId"))
      case Some(shipment) =>
        // Validate seller authorization
        if (shipment.sellerId != sellerId) {
          logger.warn(s"Seller $sellerId attempted to update tracking for shipment $shipmentId owned by ${shipment.sellerId}")
          Future.failed(new SecurityException("Access denied: shipment belongs to different seller"))
        } else {
          // Update tracking in Order Service
          orderServiceClient.updateShipmentTracking(shipmentId, trackingNumber, carrier).flatMap { updatedShipment =>
            // Publish event
            eventProducer.publishShipmentTrackingUpdatedEvent(
              shipmentId = shipmentId,
              orderId = shipment.orderId,
              sellerId = sellerId,
              trackingNumber = trackingNumber,
              carrier = carrier
            ).map { _ =>
              updatedShipment
            }
          }
        }
    }
  }
  
  /**
   * Processes a carrier webhook.
   * Maps carrier status to shipment status and updates accordingly.
   */
  def processCarrierWebhook(
    carrier: String,
    trackingNumber: String,
    carrierStatus: String,
    metadata: Option[Map[String, String]]
  ): Future[Option[Shipment]] = {
    logger.info(s"Received webhook from $carrier for tracking $trackingNumber with status $carrierStatus")
    
    // Find shipment by tracking number
    orderServiceClient.getShipmentByTrackingNumber(trackingNumber).flatMap {
      case None =>
        logger.warn(s"Shipment not found for tracking number: $trackingNumber")
        Future.successful(None)
      case Some(shipment) =>
        // Map carrier status to shipment status
        val shipmentStatus = mapCarrierStatusToShipmentStatus(carrier, carrierStatus)
        
        // Only update if status has changed
        if (shipment.status == shipmentStatus) {
          logger.debug(s"Shipment ${shipment.id} already has status $shipmentStatus, skipping update")
          Future.successful(Some(shipment))
        } else {
          // Determine timestamps based on status
          val shippedAt = if (shipmentStatus == "SHIPPED" && shipment.status != "SHIPPED") {
            Some(Instant.now())
          } else {
            shipment.shippedAt
          }
          
          val deliveredAt = if (shipmentStatus == "DELIVERED" && shipment.status != "DELIVERED") {
            Some(Instant.now())
          } else {
            shipment.deliveredAt
          }
          
          // Update shipment status
          orderServiceClient.updateShipmentStatus(shipment.id, shipmentStatus, shippedAt, deliveredAt).map { updatedShipment =>
            // Publish event for status update
            eventProducer.publishShipmentStatusUpdatedEvent(
              shipmentId = updatedShipment.id,
              orderId = updatedShipment.orderId,
              sellerId = updatedShipment.sellerId,
              status = shipmentStatus,
              previousStatus = shipment.status
            )
            
            // Publish specific events for shipped/delivered
            if (shipmentStatus == "SHIPPED" && shipment.status != "SHIPPED") {
              updatedShipment.trackingNumber.foreach { tn =>
                updatedShipment.carrier.foreach { c =>
                  eventProducer.publishShipmentShippedEvent(
                    shipmentId = updatedShipment.id,
                    orderId = updatedShipment.orderId,
                    sellerId = updatedShipment.sellerId,
                    trackingNumber = tn,
                    carrier = c,
                    shippedAt = shippedAt.get
                  )
                }
              }
            }
            
            if (shipmentStatus == "DELIVERED" && shipment.status != "DELIVERED") {
              eventProducer.publishShipmentDeliveredEvent(
                shipmentId = updatedShipment.id,
                orderId = updatedShipment.orderId,
                sellerId = updatedShipment.sellerId,
                deliveredAt = deliveredAt.get
              )
            }
            
            Some(updatedShipment)
          }.recover { case e =>
            logger.error(s"Failed to process carrier webhook for tracking $trackingNumber", e)
            None
          }
        }
    }
  }
  
  /**
   * Validates status transitions.
   */
  private def isValidStatusTransition(currentStatus: String, newStatus: String): Boolean = {
    val validTransitions = Map(
      "PENDING" -> List("PROCESSING", "CANCELLED"),
      "PROCESSING" -> List("SHIPPED", "CANCELLED"),
      "SHIPPED" -> List("IN_TRANSIT", "DELIVERED", "RETURNED"),
      "IN_TRANSIT" -> List("OUT_FOR_DELIVERY", "DELIVERED", "RETURNED"),
      "OUT_FOR_DELIVERY" -> List("DELIVERED", "RETURNED"),
      "DELIVERED" -> List(),  // Terminal state
      "CANCELLED" -> List(),  // Terminal state
      "RETURNED" -> List()    // Terminal state
    )
    
    validTransitions.get(currentStatus)
      .exists(_.contains(newStatus))
  }
  
  /**
   * Maps carrier-specific status to our shipment status.
   */
  private def mapCarrierStatusToShipmentStatus(carrier: String, carrierStatus: String): String = {
    val normalizedStatus = carrierStatus.toUpperCase
    
    // Common mappings
    normalizedStatus match {
      case s if s.contains("PENDING") || s.contains("AWAITING") => "PENDING"
      case s if s.contains("PROCESSING") || s.contains("PREPARING") => "PROCESSING"
      case s if s.contains("SHIPPED") || s.contains("PICKED_UP") || s.contains("PICKUP") => "SHIPPED"
      case s if s.contains("IN_TRANSIT") || s.contains("TRANSIT") => "IN_TRANSIT"
      case s if s.contains("OUT_FOR_DELIVERY") || s.contains("OUT FOR DELIVERY") => "OUT_FOR_DELIVERY"
      case s if s.contains("DELIVERED") => "DELIVERED"
      case s if s.contains("CANCELLED") || s.contains("CANCELED") => "CANCELLED"
      case s if s.contains("RETURNED") || s.contains("RETURN") => "RETURNED"
      case _ => "IN_TRANSIT"  // Default fallback
    }
  }
}
