package models

import java.time.Instant
import java.util.UUID

case class RefreshToken(
  id: UUID,
  tokenHash: String,
  userId: UUID,
  auth0UserId: String,
  expiresAt: Instant,
  createdAt: Instant
)
