package repositories

import anorm._
import anorm.SqlParser._
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import models.User
import play.api.db.Database
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class UserRepository @Inject()(db: Database)(implicit ec: ExecutionContext) {

  private val userParser = {
    get[UUID]("id") ~
    get[String]("email") ~
    get[String]("auth0_user_id") ~
    get[String]("firstname") ~
    get[String]("lastname") ~
    get[String]("account_type") ~
    get[Instant]("created_at") ~
    get[Instant]("updated_at") map {
      case id ~ email ~ auth0UserId ~ firstname ~ lastname ~ accountTypeStr ~ createdAt ~ updatedAt =>
        val accountType = models.AccountType.fromString(accountTypeStr).getOrElse {
          throw new IllegalStateException(s"Invalid account_type in database: '$accountTypeStr'. Database constraint should prevent this.")
        }
        User(id, email, auth0UserId, firstname, lastname, accountType, createdAt, updatedAt)
    }
  }

  def create(user: User): Future[User] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        INSERT INTO users (id, email, auth0_user_id, firstname, lastname, account_type, created_at, updated_at)
        VALUES (${user.id}, ${user.email}, ${user.auth0UserId}, ${user.firstname}, ${user.lastname}, ${user.accountType.value}, ${user.createdAt}, ${user.updatedAt})
      """.executeInsert()
      user
    }
  }

  def findById(id: UUID): Future[Option[User]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        SELECT id, email, auth0_user_id, firstname, lastname, account_type, created_at, updated_at
        FROM users
        WHERE id = $id
      """.as(userParser.singleOpt)
    }
  }

  def findByEmail(email: String): Future[Option[User]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        SELECT id, email, auth0_user_id, firstname, lastname, account_type, created_at, updated_at
        FROM users
        WHERE email = $email
      """.as(userParser.singleOpt)
    }
  }

  def findByAuth0UserId(auth0UserId: String): Future[Option[User]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        SELECT id, email, auth0_user_id, firstname, lastname, account_type, created_at, updated_at
        FROM users
        WHERE auth0_user_id = $auth0UserId
      """.as(userParser.singleOpt)
    }
  }

  def updateAccountType(id: UUID, accountType: models.AccountType): Future[Option[User]] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        UPDATE users
        SET account_type = ${accountType.value}, updated_at = now()
        WHERE id = $id
        RETURNING id, email, auth0_user_id, firstname, lastname, account_type, created_at, updated_at
      """.as(userParser.singleOpt)
    }
  }
}
