"""add product inventory table

Revision ID: 003
Revises: 002
Create Date: 2024-01-20 10:00:00.000000

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision = '003'
down_revision = '002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Create product_inventory table for denormalized inventory data
    op.create_table(
        'product_inventory',
        sa.Column('product_id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('available_quantity', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('total_quantity', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('reserved_quantity', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('allocated_quantity', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), onupdate=sa.func.now(), nullable=False),
        sa.ForeignKeyConstraint(['product_id'], ['products.id'], ondelete='CASCADE'),
    )
    
    # Create index for efficient sorting by availability
    op.create_index('idx_product_inventory_available', 'product_inventory', ['available_quantity'])
    op.create_index('idx_product_inventory_product', 'product_inventory', ['product_id'])


def downgrade() -> None:
    op.drop_index('idx_product_inventory_product', table_name='product_inventory')
    op.drop_index('idx_product_inventory_available', table_name='product_inventory')
    op.drop_table('product_inventory')
