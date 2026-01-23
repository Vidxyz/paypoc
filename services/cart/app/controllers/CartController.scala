package controllers

import auth.JwtValidator
import models.{AccountType, AddCartItemRequest, CartResponse, CheckoutResponse, UpdateCartItemRequest}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request, Result}
import services.CartService
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID
import javax.inject.Inject

class CartController @Inject()(
  cc: ControllerComponents,
  cartService: CartService,
  jwtValidator: JwtValidator
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = Logger(getClass)

  /**
   * Get current cart - BUYER or ADMIN only
   */
  def getCart: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    extractTokenAndValidate { (token, buyerId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      cartService.getOrCreateCart(buyerId).map { cart =>
        Ok(Json.toJson(CartResponse.fromCart(cart)))
      }
    }
  }

  /**
   * Add item to cart - BUYER or ADMIN only
   */
  def addItem: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    extractTokenAndValidate { (token, buyerId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      request.body.asJson match {
        case Some(json) =>
          json.validate[AddCartItemRequest].fold(
            errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request data", "details" -> errors.toString()))),
            addRequest => {
              cartService.addItem(buyerId, addRequest.productId, addRequest.quantity, token).map { cart =>
                Ok(Json.toJson(CartResponse.fromCart(cart)))
              }.recover {
                case e: IllegalArgumentException => BadRequest(Json.obj("error" -> e.getMessage))
                case e: Exception =>
                  logger.error("Error adding item to cart", e)
                  InternalServerError(Json.obj("error" -> "Failed to add item to cart"))
              }
            }
          )
        case None =>
          Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
      }
    }
  }

  /**
   * Update item quantity - BUYER or ADMIN only
   */
  def updateItem(itemId: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    extractTokenAndValidate { (token, buyerId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      cartService.getOrCreateCart(buyerId).flatMap { cart =>
        val itemIndex = cart.items.indexWhere(_.itemId == itemId)
        
        if (itemIndex < 0) {
          Future.successful(NotFound(Json.obj("error" -> s"Cart item not found: $itemId")))
        } else {
          request.body.asJson match {
            case Some(json) =>
              json.validate[UpdateCartItemRequest].fold(
                errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request data", "details" -> errors.toString()))),
                updateRequest => {
                  cartService.updateItemQuantity(cart, itemIndex, updateRequest.quantity, token).map { updatedCart =>
                    Ok(Json.toJson(CartResponse.fromCart(updatedCart)))
                  }.recover {
                    case e: IllegalArgumentException => BadRequest(Json.obj("error" -> e.getMessage))
                    case e: Exception =>
                      logger.error("Error updating item quantity", e)
                      InternalServerError(Json.obj("error" -> "Failed to update item quantity"))
                  }
                }
              )
            case None =>
              Future.successful(BadRequest(Json.obj("error" -> "Request body must be JSON")))
          }
        }
      }
    }
  }

  /**
   * Remove item from cart - BUYER or ADMIN only
   */
  def removeItem(itemId: UUID): Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    extractTokenAndValidate { (token, buyerId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      cartService.getOrCreateCart(buyerId).flatMap { cart =>
        cartService.removeItem(cart, itemId, token).map { updatedCart =>
          Ok(Json.toJson(CartResponse.fromCart(updatedCart)))
        }.recover {
          case e: NoSuchElementException => NotFound(Json.obj("error" -> e.getMessage))
          case e: Exception =>
            logger.error("Error removing item from cart", e)
            InternalServerError(Json.obj("error" -> "Failed to remove item from cart"))
        }
      }
    }
  }

  /**
   * Initiate checkout - BUYER or ADMIN only
   */
  def checkout: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    extractTokenAndValidate { (token, buyerId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      // Parse delivery details from request body (required)
      val deliveryDetails = request.body.asJson.flatMap { json =>
        (json \ "delivery_details").asOpt[play.api.libs.json.JsObject].map { detailsObj =>
          models.DeliveryDetails(
            fullName = (detailsObj \ "full_name").asOpt[String].getOrElse(""),
            address = (detailsObj \ "address").asOpt[String].getOrElse(""),
            city = (detailsObj \ "city").asOpt[String].getOrElse(""),
            province = (detailsObj \ "province").asOpt[String].getOrElse(""),
            postalCode = (detailsObj \ "postal_code").asOpt[String].getOrElse(""),
            country = (detailsObj \ "country").asOpt[String].getOrElse("Canada"),
            phone = (detailsObj \ "phone").asOpt[String].getOrElse("")
          )
        }
      }
      
      // Validate that delivery details are provided
      if (deliveryDetails.isEmpty) {
        Future.successful(BadRequest(Json.obj("error" -> "Delivery details are required")))
      } else {
        cartService.checkout(buyerId, token, deliveryDetails).map { checkoutResponse =>
          Ok(Json.toJson(checkoutResponse))
        }.recover {
          case e: IllegalArgumentException => BadRequest(Json.obj("error" -> e.getMessage))
          case e: Exception =>
            logger.error("Error initiating checkout", e)
            InternalServerError(Json.obj("error" -> "Failed to initiate checkout"))
        }
      }
    }
  }

  /**
   * Get cart history - BUYER or ADMIN only
   */
  def getCartHistory: Action[AnyContent] = Action.async { implicit request: Request[AnyContent] =>
    extractTokenAndValidate { (token, buyerId, accountType) =>
      requireBuyerOrAdmin(accountType)
      
      cartService.getCartHistory(buyerId).map { history =>
        // TODO: Convert CartEntity to CartHistoryResponse
        Ok(Json.arr())  // Placeholder
      }
    }
  }

  private def extractTokenAndValidate[T](f: (String, String, AccountType) => Future[Result])(implicit request: Request[AnyContent]): Future[Result] = {
    val authHeader = request.headers.get("Authorization")
    val token = authHeader.flatMap { header =>
      if (header.startsWith("Bearer ")) {
        Some(header.substring(7))
      } else {
        None
      }
    }

    token match {
      case None =>
        logger.warn(s"Request attempted without bearer token from ${request.remoteAddress}")
        Future.successful(
          Unauthorized(Json.obj("error" -> "Missing or invalid Authorization header. Bearer token required."))
        )
      case Some(jwtToken) =>
        val accountTypeOpt = jwtValidator.validateAndExtractAccountType(jwtToken)
        val userIdOpt = jwtValidator.validateAndExtractUserId(jwtToken)
        
        (accountTypeOpt, userIdOpt) match {
          case (None, _) | (_, None) =>
            logger.warn(s"Request attempted with invalid or expired JWT token from ${request.remoteAddress}")
            Future.successful(
              Unauthorized(Json.obj("error" -> "Invalid or expired bearer token"))
            )
          case (Some(accountType), Some(userId)) =>
            f(jwtToken, userId, accountType)
        }
    }
  }

  private def requireBuyerOrAdmin(accountType: AccountType): Unit = {
    if (accountType != AccountType.BUYER && accountType != AccountType.ADMIN) {
      throw new SecurityException("Only BUYER or ADMIN users can access this endpoint")
    }
  }
}

