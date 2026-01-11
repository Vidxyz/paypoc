package models

import java.time.Instant
import java.util.UUID

sealed trait AccountType {
  def value: String
}

object AccountType {
  case object BUYER extends AccountType { val value = "BUYER" }
  case object SELLER extends AccountType { val value = "SELLER" }
  case object ADMIN extends AccountType { val value = "ADMIN" }

  def fromString(s: String): Option[AccountType] = s match {
    case "BUYER" => Some(BUYER)
    case "SELLER" => Some(SELLER)
    case "ADMIN" => Some(ADMIN)
    case _ => None
  }

  implicit val accountTypeReads: play.api.libs.json.Reads[AccountType] = play.api.libs.json.Reads[AccountType] {
    case play.api.libs.json.JsString(s) => AccountType.fromString(s)
      .map(play.api.libs.json.JsSuccess(_))
      .getOrElse(play.api.libs.json.JsError(s"Invalid account type: '$s'. Must be one of: BUYER, SELLER, ADMIN"))
    case _ => play.api.libs.json.JsError("Expected string for account_type field")
  }

  implicit val accountTypeWrites: play.api.libs.json.Writes[AccountType] = play.api.libs.json.Writes[AccountType] {
    accountType => play.api.libs.json.JsString(accountType.value)
  }
}

case class User(
  id: UUID,
  email: String,
  auth0UserId: String,
  firstname: String,
  lastname: String,
  accountType: AccountType,
  createdAt: Instant,
  updatedAt: Instant
)

case class SignupRequest(
  email: String,
  password: String,
  firstname: String,
  lastname: String,
  accountType: AccountType
)

case class UserResponse(
  id: UUID,
  email: String,
  firstname: String,
  lastname: String,
  accountType: AccountType
)

object UserResponse {
  def fromUser(user: User): UserResponse = {
    UserResponse(
      id = user.id,
      email = user.email,
      firstname = user.firstname,
      lastname = user.lastname,
      accountType = user.accountType
    )
  }
}
