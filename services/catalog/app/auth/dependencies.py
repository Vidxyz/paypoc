from fastapi import HTTPException, status, Depends, Security
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from app.auth.jwt_validator import jwt_validator
import logging

logger = logging.getLogger(__name__)


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Security(HTTPBearer())
) -> dict:
    """Dependency to extract and validate JWT token
    
    Works with Security(security) authentication scheme.
    """
    if not credentials:
        logger.warning("Authentication credentials missing")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization header missing"
        )
    
    token = credentials.credentials
    
    try:
        logger.debug(f"Verifying JWT token for user")
        # Verify token and extract claims
        payload = jwt_validator.verify_token(token)
        
        # Extract user information from payload
        custom_namespace = "https://buyit.local/"
        user_id = payload.get(custom_namespace + "user_id")
        # Email can be in standard location or namespaced location
        email = payload.get(custom_namespace + "email")
        account_type = payload.get(custom_namespace + "account_type")
        
        if not user_id:
            logger.warning("Token missing user_id claim")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token missing user_id claim"
            )
        
        if not account_type:
            logger.warning("Token missing account_type claim")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token missing account_type claim"
            )
        
        if not email:
            logger.warning("Token missing email claim")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token missing email claim"
            )
        
        logger.info(f"Authenticated user: {email} (user_id: {user_id}, account_type: {account_type})")
        
        return {
            "user_id": user_id,
            "email": email,
            "account_type": account_type,
            "payload": payload
        }
    except HTTPException:
        # Re-raise HTTP exceptions (they already have proper status codes)
        raise
    except Exception as e:
        logger.error(f"Error authenticating user: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Authentication failed"
        )


async def require_account_type(account_type: str):
    """Dependency factory to require specific account type"""
    async def dependency(current_user: dict = None):
        if current_user is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Authentication required"
            )
        if current_user.get("account_type") != account_type:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Requires {account_type} account type"
            )
        return current_user
    return dependency


async def require_seller_or_admin(
    current_user: dict = Depends(get_current_user)
) -> dict:
    """Dependency to require SELLER or ADMIN account type"""
    account_type = current_user.get("account_type")
    if account_type not in ["SELLER", "ADMIN"]:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Requires SELLER or ADMIN account type"
        )
    return current_user

