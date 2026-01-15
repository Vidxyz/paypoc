package models

import play.api.libs.json.{Json, Reads, Writes}

object package {
  // Custom JSON serialization for AccountType
  implicit val accountTypeReads: Reads[AccountType] = Reads[AccountType] {
    case play.api.libs.json.JsString(s) => AccountType.fromString(s)
      .map(play.api.libs.json.JsSuccess(_))
      .getOrElse(play.api.libs.json.JsError(s"Invalid account type: '$s'. Must be one of: BUYER, SELLER, ADMIN"))
    case _ => play.api.libs.json.JsError("Expected string for account_type field")
  }

  implicit val accountTypeWrites: Writes[AccountType] = Writes[AccountType] {
    accountType => play.api.libs.json.JsString(accountType.value)
  }
}

