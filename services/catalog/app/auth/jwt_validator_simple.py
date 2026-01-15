"""
Simplified JWT validator - will be enhanced with proper JWKS support
For now, this is a placeholder that validates token structure
"""
import jwt
import requests
import logging
from typing import Optional, Dict
from fastapi import HTTPException, status
from app.config import settings

logger = logging.getLogger(__name__)


class JWTValidator:
    def __init__(self):
        self.auth0_domain = settings.auth0_domain
        self.audience = settings.auth0_audience
    
    def verify_token(self, token: str) -> Dict:
        """
        Verify JWT token.
        TODO: Implement proper JWKS-based verification
        For now, this is a placeholder that decodes the token
        """
        try:
            # For now, decode without verification
            # TODO: Implement proper JWKS-based verification
            # This should use pyjwt with JWKS
            decoded = jwt.decode(
                token,
                options={"verify_signature": False}  # TODO: Enable signature verification
            )
            return decoded
        except jwt.InvalidTokenError as e:
            logger.warning(f"JWT verification failed: {e}")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid authentication credentials"
            )
    
    def extract_user_id(self, token: str) -> Optional[str]:
        """Extract user_id from JWT token"""
        payload = self.verify_token(token)
        custom_namespace = "https://buyit.local/"
        user_id = payload.get(custom_namespace + "user_id")
        if not user_id:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token missing user_id claim"
            )
        return user_id
    
    def extract_email(self, token: str) -> Optional[str]:
        """Extract email from JWT token"""
        payload = self.verify_token(token)
        return payload.get("email")
    
    def extract_account_type(self, token: str) -> Optional[str]:
        """Extract account_type from JWT token"""
        payload = self.verify_token(token)
        custom_namespace = "https://buyit.local/"
        account_type = payload.get(custom_namespace + "account_type")
        if not account_type:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token missing account_type claim"
            )
        return account_type


jwt_validator = JWTValidator()

