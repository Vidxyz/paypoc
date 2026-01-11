package services

import models.{SignupRequest, User, UserCreatedEvent}
import play.api.Logger
import repositories.UserRepository
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class UserService @Inject()(
  userRepository: UserRepository,
  auth0UserService: Auth0UserService,
  eventProducer: UserEventProducer
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  def signup(request: SignupRequest): Future[User] = {
    for {
      auth0UserId <- auth0UserService.createUser(
        request.email,
        request.password,
        request.firstname,
        request.lastname
      )
      userId = UUID.randomUUID()
      now = Instant.now()
      user = User(
        id = userId,
        email = request.email,
        auth0UserId = auth0UserId,
        firstname = request.firstname,
        lastname = request.lastname,
        accountType = request.accountType,
        createdAt = now,
        updatedAt = now
      )
      savedUser <- userRepository.create(user)
      _ <- eventProducer.publishUserCreatedEvent(
        UserCreatedEvent.create(
          userId = savedUser.id,
          email = savedUser.email,
          firstname = savedUser.firstname,
          lastname = savedUser.lastname,
          auth0UserId = savedUser.auth0UserId,
          accountType = savedUser.accountType
        )
      ).recover { case e: Exception =>
        logger.error(s"Failed to publish user.created event for user ${savedUser.id}", e)
      }
    } yield savedUser
  }

  def getUser(id: UUID): Future[Option[User]] = {
    userRepository.findById(id)
  }

  def getUserByEmail(email: String): Future[Option[User]] = {
    userRepository.findByEmail(email)
  }

  def getUserByAuth0Id(auth0UserId: String): Future[Option[User]] = {
    userRepository.findByAuth0UserId(auth0UserId)
  }

  def updateAccountType(id: UUID, accountType: models.AccountType): Future[Option[User]] = {
    userRepository.updateAccountType(id, accountType)
  }
}
