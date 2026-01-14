package models

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
}

