"""
JWT validator with JWKS-based verification for Auth0 tokens
"""
import jwt
from jwt import PyJWKClient
import logging
from typing import Optional, Dict
from fastapi import HTTPException, status
from app.config import settings

logger = logging.getLogger(__name__)


class JWTValidator:
    def __init__(self):
        self.auth0_domain = settings.auth0_domain
        self.audience = settings.auth0_audience
        self.issuer = f"https://{self.auth0_domain}/"
        self.jwks_url = f"https://{self.auth0_domain}/.well-known/jwks.json"
        
        # Create JWKS client with caching
        self.jwks_client = PyJWKClient(self.jwks_url, cache_keys=True, max_cached_keys=10)
    
    def verify_token(self, token: str) -> Dict:
        """
        Verify JWT token using JWKS.
        Returns decoded token payload if valid.
        """
        try:
            # Get the signing key from JWKS
            signing_key = self.jwks_client.get_signing_key_from_jwt(token)
            
            # Verify and decode the token
            payload = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256"],
                audience=self.audience if self.audience else None,
                issuer=self.issuer,
                options={
                    "verify_signature": True,
                    "verify_aud": bool(self.audience),
                    "verify_iss": True,
                    "verify_exp": True,
                }
            )
            return payload
        except jwt.ExpiredSignatureError:
            logger.warning("JWT token has expired")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token has expired"
            )
        except jwt.InvalidTokenError as e:
            logger.warning(f"JWT verification failed: {e}")
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid authentication credentials"
            )
        except Exception as e:
            logger.error(f"Error verifying JWT token: {e}")
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Authentication service unavailable"
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
        """Extract email from JWT token - checks both standard and namespaced locations"""
        payload = self.verify_token(token)
        custom_namespace = "https://buyit.local/"
        # Email can be in standard location or namespaced location
        return payload.get(custom_namespace + "email")
    
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
