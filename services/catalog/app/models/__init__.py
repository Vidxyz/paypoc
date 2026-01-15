# Package exports - these allow cleaner imports like:
# from app.models import Product, Category
# Used by alembic/env.py for migration autogenerate
from app.models.product import Product
from app.models.category import Category

