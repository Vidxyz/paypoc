package models

import play.api.libs.json.{Json, Reads, Writes}

case class UpdateShipmentStatusRequest(
  status: String
)

object UpdateShipmentStatusRequest {
  implicit val reads: Reads[UpdateShipmentStatusRequest] = Json.reads[UpdateShipmentStatusRequest]
  implicit val writes: Writes[UpdateShipmentStatusRequest] = Json.writes[UpdateShipmentStatusRequest]
}

case class AddTrackingRequest(
  trackingNumber: String,
  carrier: String
)

object AddTrackingRequest {
  implicit val reads: Reads[AddTrackingRequest] = Json.reads[AddTrackingRequest]
  implicit val writes: Writes[AddTrackingRequest] = Json.writes[AddTrackingRequest]
}

case class CarrierWebhookRequest(
  carrier: String,
  trackingNumber: String,
  status: String,
  timestamp: Option[String],
  metadata: Option[Map[String, String]]
)

object CarrierWebhookRequest {
  implicit val reads: Reads[CarrierWebhookRequest] = Json.reads[CarrierWebhookRequest]
  implicit val writes: Writes[CarrierWebhookRequest] = Json.writes[CarrierWebhookRequest]
}
