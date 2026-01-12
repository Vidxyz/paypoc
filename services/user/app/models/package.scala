package object models {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
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

  // Custom Reads/Writes for SignupRequest to map account_type (JSON) -> accountType (Scala)
  // account_type is optional - defaults to BUYER if not provided (will be overridden by controllers)
  implicit val signupRequestReads: Reads[SignupRequest] = (
    (__ \ "email").read[String] and
    (__ \ "password").read[String] and
    (__ \ "firstname").read[String] and
    (__ \ "lastname").read[String] and
    (__ \ "account_type").readNullable[AccountType].map(_.getOrElse(AccountType.BUYER))
  )(SignupRequest.apply _)

  implicit val signupRequestWrites: Writes[SignupRequest] = (
    (__ \ "email").write[String] and
    (__ \ "password").write[String] and
    (__ \ "firstname").write[String] and
    (__ \ "lastname").write[String] and
    (__ \ "account_type").write[AccountType]
  )(unlift(SignupRequest.unapply))

  // Format that combines the custom Reads and Writes
  implicit val signupRequestFormat: Format[SignupRequest] = Format(signupRequestReads, signupRequestWrites)

  // Custom Writes for UserResponse to map accountType (Scala) -> account_type (JSON)
  implicit val userResponseWrites: Writes[UserResponse] = (
    (__ \ "id").write[UUID] and
    (__ \ "email").write[String] and
    (__ \ "firstname").write[String] and
    (__ \ "lastname").write[String] and
    (__ \ "account_type").write[AccountType]
  )(unlift(UserResponse.unapply))

  implicit val userCreatedEventWrites: Writes[UserCreatedEvent] = Json.writes[UserCreatedEvent]
  implicit val userCreatedEventReads: Reads[UserCreatedEvent] = Json.reads[UserCreatedEvent]
}
