package object models {
  import play.api.libs.json.{Json, Reads, Writes}

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

  // Custom JSON serialization for CartStatus
  implicit lazy val cartStatusReads: Reads[CartStatus] = Reads[CartStatus] {
    case play.api.libs.json.JsString(s) => CartStatus.fromString(s)
      .map(play.api.libs.json.JsSuccess(_))
      .getOrElse(play.api.libs.json.JsError(s"Invalid cart status: '$s'. Must be one of: ACTIVE, CHECKOUT, COMPLETED, ABANDONED, EXPIRED"))
    case _ => play.api.libs.json.JsError("Expected string for status field")
  }

  implicit lazy val cartStatusWrites: Writes[CartStatus] = Writes[CartStatus] {
    status => play.api.libs.json.JsString(status.value)
  }
}

