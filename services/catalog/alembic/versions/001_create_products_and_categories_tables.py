"""create products and categories tables

Revision ID: 001
Revises: 
Create Date: 2024-01-15 10:00:00.000000

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision = '001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Create categories table
    op.create_table(
        'categories',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text('gen_random_uuid()')),
        sa.Column('name', sa.Text(), nullable=False),
        sa.Column('slug', sa.Text(), nullable=False, unique=True),
        sa.Column('description', sa.Text()),
        sa.Column('parent_id', postgresql.UUID(as_uuid=True)),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index('idx_parent', 'categories', ['parent_id'])
    
    # Create products table
    op.create_table(
        'products',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text('gen_random_uuid()')),
        sa.Column('seller_id', sa.Text(), nullable=False),
        sa.Column('sku', sa.Text(), nullable=False),
        sa.Column('name', sa.Text(), nullable=False),
        sa.Column('description', sa.Text()),
        sa.Column('category_id', postgresql.UUID(as_uuid=True)),
        sa.Column('price_cents', sa.BigInteger(), nullable=False),
        sa.Column('currency', sa.String(3), nullable=False),
        sa.Column('status', sa.String(20), nullable=False, server_default='DRAFT'),
        sa.Column('attributes', postgresql.JSONB()),
        sa.Column('images', postgresql.JSONB()),  # Array of image IDs (provider-specific, e.g. Cloudinary public_id)
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.Column('deleted_at', sa.DateTime(timezone=True)),
        sa.CheckConstraint('price_cents > 0', name='positive_price'),
        sa.CheckConstraint("currency ~ '^[A-Z]{3}$'", name='currency_format'),
        sa.CheckConstraint("status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DELETED')", name='status_valid'),
    )
    op.create_index('idx_seller_status', 'products', ['seller_id', 'status'])
    op.create_index('idx_category', 'products', ['category_id'])
    op.create_index('idx_status', 'products', ['status'], postgresql_where=sa.text('deleted_at IS NULL'))
    op.create_unique_constraint('uq_seller_sku', 'products', ['seller_id', 'sku'])


def downgrade() -> None:
    op.drop_table('products')
    op.drop_table('categories')

