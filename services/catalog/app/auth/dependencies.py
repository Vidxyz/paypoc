from fastapi import Header, HTTPException, status, Depends
from typing import Optional
from app.auth.jwt_validator import jwt_validator

logger = None
try:
    import logging
    logger = logging.getLogger(__name__)
except:
    pass


async def get_current_user(
    authorization: Optional[str] = Header(None)
) -> dict:
    """Dependency to extract and validate JWT token
    
    Works with both Header-based and Security-based authentication.
    When Security(security) is used, the header is still available.
    """
    if not authorization:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization header missing"
        )
    
    try:
        # Extract token from "Bearer <token>"
        scheme, token = authorization.split()
        if scheme.lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid authentication scheme"
            )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authorization header format"
        )
    
    payload = jwt_validator.verify_token(token)
    user_id = jwt_validator.extract_user_id(token)
    email = jwt_validator.extract_email(token)
    account_type = jwt_validator.extract_account_type(token)
    
    return {
        "user_id": user_id,
        "email": email,
        "account_type": account_type,
        "payload": payload
    }


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

