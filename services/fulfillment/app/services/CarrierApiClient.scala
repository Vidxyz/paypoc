package services

import scala.concurrent.Future

/**
 * Trait for carrier API integration.
 * 
 * Stub implementation for now - future carriers (UPS, FedEx, DHL) will implement this.
 */
trait CarrierApiClient {
  /**
   * Generates a shipping label for a shipment.
   * 
   * @param shipmentId Shipment ID
   * @param fromAddress Origin address
   * @param toAddress Destination address
   * @param weight Weight in grams
   * @param dimensions Package dimensions (length, width, height in cm)
   * @return Future containing tracking number and label URL
   */
  def generateLabel(
    shipmentId: String,
    fromAddress: Address,
    toAddress: Address,
    weight: Int,
    dimensions: Dimensions
  ): Future[LabelResponse]
  
  /**
   * Looks up tracking information for a tracking number.
   * 
   * @param trackingNumber Tracking number
   * @return Future containing current status and tracking events
   */
  def lookupTracking(trackingNumber: String): Future[TrackingResponse]
}

case class Address(
  name: String,
  street1: String,
  street2: Option[String],
  city: String,
  state: String,
  postalCode: String,
  country: String
)

case class Dimensions(
  length: Int,  // cm
  width: Int,   // cm
  height: Int   // cm
)

case class LabelResponse(
  trackingNumber: String,
  labelUrl: String,
  carrier: String
)

case class TrackingResponse(
  trackingNumber: String,
  status: String,
  carrier: String,
  events: List[TrackingEvent]
)

case class TrackingEvent(
  timestamp: String,
  status: String,
  location: Option[String],
  description: String
)

/**
 * Stub implementation - returns mock data for now.
 */
class StubCarrierApiClient extends CarrierApiClient {
  import scala.concurrent.ExecutionContext.Implicits.global
  
  override def generateLabel(
    shipmentId: String,
    fromAddress: Address,
    toAddress: Address,
    weight: Int,
    dimensions: Dimensions
  ): Future[LabelResponse] = {
    Future.successful(
      LabelResponse(
        trackingNumber = s"STUB-$shipmentId",
        labelUrl = s"https://stub.carrier.com/labels/$shipmentId",
        carrier = "STUB"
      )
    )
  }
  
  override def lookupTracking(trackingNumber: String): Future[TrackingResponse] = {
    Future.successful(
      TrackingResponse(
        trackingNumber = trackingNumber,
        status = "IN_TRANSIT",
        carrier = "STUB",
        events = List(
          TrackingEvent(
            timestamp = java.time.Instant.now().toString,
            status = "IN_TRANSIT",
            location = Some("Distribution Center"),
            description = "Package in transit"
          )
        )
      )
    )
  }
}
