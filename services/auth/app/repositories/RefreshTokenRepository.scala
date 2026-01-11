package repositories

import anorm._
import models.RefreshToken
import play.api.db.Database
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class RefreshTokenRepository @Inject()(
  db: Database
)(implicit ec: ExecutionContext) {

  def create(refreshToken: RefreshToken): Future[RefreshToken] = {
    // TODO: Implement refresh token storage
    Future.successful(refreshToken)
  }

  def findByTokenHash(tokenHash: String): Future[Option[RefreshToken]] = {
    // TODO: Implement refresh token lookup
    Future.successful(None)
  }

  def delete(tokenHash: String): Future[Unit] = {
    // TODO: Implement refresh token deletion
    Future.unit
  }
}
