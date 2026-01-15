from sqlalchemy import create_engine, text
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from app.config import settings
from alembic import command
from alembic.config import Config
import asyncio
import logging
import os

logger = logging.getLogger(__name__)

engine = create_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_size=10,
    max_overflow=20,
    connect_args={
        "connect_timeout": 5,  # 5 second connection timeout
    },
    pool_timeout=10,  # 10 second timeout for getting a connection from pool
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    """Dependency for getting database session"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


async def wait_for_database(max_retries=30, retry_delay=2):
    """Wait for database to be available with retry logic"""
    # Extract database host for logging (safely)
    db_url_display = settings.database_url
    try:
        if '@' in db_url_display:
            db_url_display = db_url_display.split('@')[-1]
    except:
        pass
    
    logger.info(f"Waiting for database connection to {db_url_display}...")
    
    for attempt in range(1, max_retries + 1):
        try:
            # Try to connect and execute a simple query with timeout
            # Use a fresh connection with timeout
            test_engine = create_engine(
                settings.database_url,
                pool_pre_ping=True,
                connect_args={
                    "connect_timeout": 5,  # 5 second connection timeout
                },
                pool_timeout=5,
            )
            with test_engine.connect() as conn:
                result = conn.execute(text("SELECT 1"))
                result.fetchone()  # Actually fetch to verify query works
            test_engine.dispose()  # Close the test engine
            logger.info("Database connection successful")
            return True
        except Exception as e:
            if attempt < max_retries:
                logger.warning(f"Database connection attempt {attempt}/{max_retries} failed: {e}. Retrying in {retry_delay}s...")
                await asyncio.sleep(retry_delay)
            else:
                logger.error(f"Database connection failed after {max_retries} attempts: {e}")
                raise
    return False


async def init_db():
    """Initialize database by running Alembic migrations"""
    logger.info("Running database migrations...")
    
    # First, wait for database to be available
    try:
        await wait_for_database(max_retries=30, retry_delay=2)
    except Exception as e:
        logger.error(f"Failed to connect to database: {e}")
        raise
    
    try:
        # Find alembic.ini
        # In Docker: working directory is /app, alembic.ini is at /app/alembic.ini
        # Locally: might be in services/catalog/ or project root
        current_dir = os.getcwd()
        
        # Try current directory first (works in Docker)
        alembic_ini_path = "alembic.ini"
        if not os.path.exists(alembic_ini_path):
            # Try relative to this file (services/catalog/app/db/database.py -> services/catalog/alembic.ini)
            file_dir = os.path.dirname(os.path.abspath(__file__))
            project_root = os.path.dirname(os.path.dirname(os.path.dirname(file_dir)))
            alembic_ini_path = os.path.join(project_root, "alembic.ini")
        
        if not os.path.exists(alembic_ini_path):
            raise FileNotFoundError(
                f"Could not find alembic.ini. Current directory: {current_dir}, "
                f"Tried: alembic.ini and {alembic_ini_path}"
            )
        
        logger.info(f"Using Alembic config: {os.path.abspath(alembic_ini_path)}")
        
        # Create Alembic config
        alembic_cfg = Config(alembic_ini_path)
        
        # Override database URL from settings
        alembic_cfg.set_main_option("sqlalchemy.url", settings.database_url)
        
        logger.info("Starting Alembic migration to head...")
        
        # Run migrations with timeout protection
        # Run in a thread pool to avoid blocking the event loop
        try:
            # Use to_thread if available (Python 3.9+), otherwise use executor
            if hasattr(asyncio, 'to_thread'):
                await asyncio.wait_for(
                    asyncio.to_thread(command.upgrade, alembic_cfg, "head"),
                    timeout=60.0  # 60 second timeout for migrations
                )
            else:
                loop = asyncio.get_event_loop()
                await asyncio.wait_for(
                    loop.run_in_executor(None, command.upgrade, alembic_cfg, "head"),
                    timeout=60.0  # 60 second timeout for migrations
                )
        except asyncio.TimeoutError:
            logger.error("Database migrations timed out after 60 seconds")
            raise
        except Exception as migration_error:
            logger.error(f"Migration error: {migration_error}", exc_info=True)
            raise
        
        logger.info("Database migrations completed successfully")
    except Exception as e:
        logger.error(f"Error running migrations: {e}", exc_info=True)
        # Re-raise to prevent service from starting with failed migrations
        raise

