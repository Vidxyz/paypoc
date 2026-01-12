package models

import java.time.Instant
import java.util.UUID

case class UserCreatedEvent(
  eventType: String,
  userId: UUID,
  email: String,
  firstname: String,
  lastname: String,
  auth0UserId: String,
  accountType: AccountType,
  timestamp: Instant
)

object UserCreatedEvent {
  def create(userId: UUID, email: String, firstname: String, lastname: String, auth0UserId: String, accountType: AccountType): UserCreatedEvent = {
    UserCreatedEvent(
      eventType = "user.created",
      userId = userId,
      email = email,
      firstname = firstname,
      lastname = lastname,
      auth0UserId = auth0UserId,
      accountType = accountType,
      timestamp = Instant.now()
    )
  }
}
