package object models {
  import play.api.libs.json.{Reads, Writes}

  // Custom JSON serialization for AccountType
  implicit lazy val accountTypeReads: Reads[AccountType] = Reads[AccountType] {
    case play.api.libs.json.JsString(s) => AccountType.fromString(s)
      .map(play.api.libs.json.JsSuccess(_))
      .getOrElse(play.api.libs.json.JsError(s"Invalid account type: '$s'. Must be one of: BUYER, SELLER, ADMIN"))
    case _ => play.api.libs.json.JsError("Expected string for account_type field")
  }

  implicit lazy val accountTypeWrites: Writes[AccountType] = Writes[AccountType] {
    accountType => play.api.libs.json.JsString(accountType.value)
  }

  // Custom JSON serialization for ShipmentStatus
  implicit lazy val shipmentStatusReads: Reads[ShipmentStatus] = Reads[ShipmentStatus] {
    case play.api.libs.json.JsString(s) => ShipmentStatus.fromString(s)
      .map(play.api.libs.json.JsSuccess(_))
      .getOrElse(play.api.libs.json.JsError(s"Invalid shipment status: '$s'"))
    case _ => play.api.libs.json.JsError("Expected string for status field")
  }

  implicit lazy val shipmentStatusWrites: Writes[ShipmentStatus] = Writes[ShipmentStatus] {
    status => play.api.libs.json.JsString(status.value)
  }
}
