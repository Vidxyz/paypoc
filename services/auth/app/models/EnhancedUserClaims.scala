package models

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

  // Strict validation - throws exception for invalid values
  def fromStringStrict(s: String): AccountType = {
    fromString(s).getOrElse {
      throw new IllegalArgumentException(s"Invalid account_type: '$s'. Must be one of: BUYER, SELLER, ADMIN")
    }
  }
}

case class EnhancedUserClaims(
  sub: String,  // Auth0 user ID
  email: String,
  userId: UUID,
  accountType: AccountType,
  firstname: String,
  lastname: String
)
