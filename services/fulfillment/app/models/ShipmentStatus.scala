package models

sealed trait ShipmentStatus {
  def value: String
}

object ShipmentStatus {
  case object PENDING extends ShipmentStatus { val value = "PENDING" }
  case object PROCESSING extends ShipmentStatus { val value = "PROCESSING" }
  case object SHIPPED extends ShipmentStatus { val value = "SHIPPED" }
  case object IN_TRANSIT extends ShipmentStatus { val value = "IN_TRANSIT" }
  case object OUT_FOR_DELIVERY extends ShipmentStatus { val value = "OUT_FOR_DELIVERY" }
  case object DELIVERED extends ShipmentStatus { val value = "DELIVERED" }
  case object CANCELLED extends ShipmentStatus { val value = "CANCELLED" }
  case object RETURNED extends ShipmentStatus { val value = "RETURNED" }

  def fromString(s: String): Option[ShipmentStatus] = s match {
    case "PENDING" => Some(PENDING)
    case "PROCESSING" => Some(PROCESSING)
    case "SHIPPED" => Some(SHIPPED)
    case "IN_TRANSIT" => Some(IN_TRANSIT)
    case "OUT_FOR_DELIVERY" => Some(OUT_FOR_DELIVERY)
    case "DELIVERED" => Some(DELIVERED)
    case "CANCELLED" => Some(CANCELLED)
    case "RETURNED" => Some(RETURNED)
    case _ => None
  }
}
