package object models {
  import play.api.libs.json._
  import java.time.Instant
  import java.time.format.DateTimeFormatter
  import java.util.UUID

  implicit val instantReads: Reads[Instant] = Reads.instantReads(DateTimeFormatter.ISO_INSTANT)
  implicit val instantWrites: Writes[Instant] = Writes.temporalWrites[Instant, DateTimeFormatter](DateTimeFormatter.ISO_INSTANT)
  implicit val instantFormat: Format[Instant] = Format(instantReads, instantWrites)

  implicit val uuidReads: Reads[UUID] = Reads.uuidReads
  implicit val uuidWrites: Writes[UUID] = Writes.UuidWrites
  implicit val uuidFormat: Format[UUID] = Format(uuidReads, uuidWrites)

  implicit val accountTypeFormat: Format[AccountType] = Format(AccountType.accountTypeReads, AccountType.accountTypeWrites)

  implicit val userWrites: Writes[User] = Json.writes[User]
  implicit val userReads: Reads[User] = Json.reads[User]

  implicit val signupRequestReads: Reads[SignupRequest] = Json.reads[SignupRequest]

  implicit val userResponseWrites: Writes[UserResponse] = Json.writes[UserResponse]

  implicit val userCreatedEventWrites: Writes[UserCreatedEvent] = Json.writes[UserCreatedEvent]
  implicit val userCreatedEventReads: Reads[UserCreatedEvent] = Json.reads[UserCreatedEvent]
}
