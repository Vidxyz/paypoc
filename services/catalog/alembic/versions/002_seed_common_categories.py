"""seed_common_categories

Revision ID: 002
Revises: 001
Create Date: 2024-01-15 12:00:00.000000

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql
import uuid

# revision identifiers, used by Alembic.
revision = '002'
down_revision = '001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Get connection to execute raw SQL
    connection = op.get_bind()
    
    # Define common marketplace categories with hierarchy
    categories = [
        # Top-level categories
        {
            'id': str(uuid.uuid4()),
            'name': 'Electronics',
            'slug': 'electronics',
            'description': 'Electronic devices, accessories, and components',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Fashion',
            'slug': 'fashion',
            'description': 'Clothing, shoes, accessories, and jewelry',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Home & Garden',
            'slug': 'home-garden',
            'description': 'Furniture, decor, tools, and garden supplies',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Toys & Games',
            'slug': 'toys-games',
            'description': 'Toys, games, puzzles, and collectibles',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Sports & Outdoors',
            'slug': 'sports-outdoors',
            'description': 'Sports equipment, outdoor gear, and fitness',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Books, Movies & Music',
            'slug': 'books-movies-music',
            'description': 'Books, movies, music, and media',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Health & Beauty',
            'slug': 'health-beauty',
            'description': 'Health products, beauty items, and personal care',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Collectibles & Art',
            'slug': 'collectibles-art',
            'description': 'Collectibles, art, antiques, and memorabilia',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Automotive',
            'slug': 'automotive',
            'description': 'Car parts, accessories, and automotive supplies',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Business & Industrial',
            'slug': 'business-industrial',
            'description': 'Business equipment, industrial supplies, and tools',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Crafts & Supplies',
            'slug': 'crafts-supplies',
            'description': 'Craft materials, DIY supplies, and handmade items',
            'parent_id': None
        },
        {
            'id': str(uuid.uuid4()),
            'name': 'Food & Beverages',
            'slug': 'food-beverages',
            'description': 'Food items, beverages, and gourmet products',
            'parent_id': None
        },
    ]
    
    # Store parent IDs for subcategories
    parent_ids = {}
    
    # Insert top-level categories
    for category in categories:
        cat_id = category['id']
        parent_ids[category['slug']] = cat_id
        
        connection.execute(
            sa.text("""
                INSERT INTO categories (id, name, slug, description, parent_id, created_at)
                VALUES (:id, :name, :slug, :description, :parent_id, NOW())
            """),
            {
                'id': cat_id,
                'name': category['name'],
                'slug': category['slug'],
                'description': category['description'],
                'parent_id': category['parent_id']
            }
        )
    
    # Define subcategories
    subcategories = [
        # Electronics subcategories
        {'name': 'Cell Phones & Accessories', 'slug': 'cell-phones-accessories', 'parent': 'electronics'},
        {'name': 'Computers & Tablets', 'slug': 'computers-tablets', 'parent': 'electronics'},
        {'name': 'TV, Audio & Surveillance', 'slug': 'tv-audio-surveillance', 'parent': 'electronics'},
        {'name': 'Video Game Consoles & Games', 'slug': 'video-games', 'parent': 'electronics'},
        {'name': 'Camera & Photo', 'slug': 'camera-photo', 'parent': 'electronics'},
        {'name': 'Smart Home', 'slug': 'smart-home', 'parent': 'electronics'},
        
        # Fashion subcategories
        {'name': "Women's Clothing", 'slug': 'womens-clothing', 'parent': 'fashion'},
        {'name': "Men's Clothing", 'slug': 'mens-clothing', 'parent': 'fashion'},
        {'name': "Women's Shoes", 'slug': 'womens-shoes', 'parent': 'fashion'},
        {'name': "Men's Shoes", 'slug': 'mens-shoes', 'parent': 'fashion'},
        {'name': 'Handbags, Wallets & Cases', 'slug': 'handbags-wallets', 'parent': 'fashion'},
        {'name': 'Jewelry & Watches', 'slug': 'jewelry-watches', 'parent': 'fashion'},
        {'name': 'Accessories', 'slug': 'fashion-accessories', 'parent': 'fashion'},
        
        # Home & Garden subcategories
        {'name': 'Furniture', 'slug': 'furniture', 'parent': 'home-garden'},
        {'name': 'Home Decor', 'slug': 'home-decor', 'parent': 'home-garden'},
        {'name': 'Kitchen & Dining', 'slug': 'kitchen-dining', 'parent': 'home-garden'},
        {'name': 'Bedding & Bath', 'slug': 'bedding-bath', 'parent': 'home-garden'},
        {'name': 'Tools & Workshop Equipment', 'slug': 'tools-workshop', 'parent': 'home-garden'},
        {'name': 'Garden & Outdoor', 'slug': 'garden-outdoor', 'parent': 'home-garden'},
        {'name': 'Lighting & Ceiling Fans', 'slug': 'lighting-fans', 'parent': 'home-garden'},
        
        # Toys & Games subcategories
        {'name': 'Action Figures', 'slug': 'action-figures', 'parent': 'toys-games'},
        {'name': 'Building Toys', 'slug': 'building-toys', 'parent': 'toys-games'},
        {'name': 'Dolls & Accessories', 'slug': 'dolls-accessories', 'parent': 'toys-games'},
        {'name': 'Games & Puzzles', 'slug': 'games-puzzles', 'parent': 'toys-games'},
        {'name': 'Educational Toys', 'slug': 'educational-toys', 'parent': 'toys-games'},
        {'name': 'Collectible Card Games', 'slug': 'collectible-card-games', 'parent': 'toys-games'},
        
        # Sports & Outdoors subcategories
        {'name': 'Outdoor Sports', 'slug': 'outdoor-sports', 'parent': 'sports-outdoors'},
        {'name': 'Exercise & Fitness', 'slug': 'exercise-fitness', 'parent': 'sports-outdoors'},
        {'name': 'Team Sports', 'slug': 'team-sports', 'parent': 'sports-outdoors'},
        {'name': 'Water Sports', 'slug': 'water-sports', 'parent': 'sports-outdoors'},
        {'name': 'Winter Sports', 'slug': 'winter-sports', 'parent': 'sports-outdoors'},
        {'name': 'Camping & Hiking', 'slug': 'camping-hiking', 'parent': 'sports-outdoors'},
        
        # Books, Movies & Music subcategories
        {'name': 'Books', 'slug': 'books', 'parent': 'books-movies-music'},
        {'name': 'Movies & TV', 'slug': 'movies-tv', 'parent': 'books-movies-music'},
        {'name': 'Music', 'slug': 'music', 'parent': 'books-movies-music'},
        {'name': 'Video Games', 'slug': 'video-games-media', 'parent': 'books-movies-music'},
        
        # Health & Beauty subcategories
        {'name': 'Skincare', 'slug': 'skincare', 'parent': 'health-beauty'},
        {'name': 'Makeup', 'slug': 'makeup', 'parent': 'health-beauty'},
        {'name': 'Fragrances', 'slug': 'fragrances', 'parent': 'health-beauty'},
        {'name': 'Hair Care', 'slug': 'hair-care', 'parent': 'health-beauty'},
        {'name': 'Health & Personal Care', 'slug': 'health-personal-care', 'parent': 'health-beauty'},
        {'name': 'Vitamins & Supplements', 'slug': 'vitamins-supplements', 'parent': 'health-beauty'},
        
        # Collectibles & Art subcategories
        {'name': 'Antiques', 'slug': 'antiques', 'parent': 'collectibles-art'},
        {'name': 'Art', 'slug': 'art', 'parent': 'collectibles-art'},
        {'name': 'Coins & Paper Money', 'slug': 'coins-paper-money', 'parent': 'collectibles-art'},
        {'name': 'Stamps', 'slug': 'stamps', 'parent': 'collectibles-art'},
        {'name': 'Trading Cards', 'slug': 'trading-cards', 'parent': 'collectibles-art'},
        {'name': 'Memorabilia', 'slug': 'memorabilia', 'parent': 'collectibles-art'},
        
        # Automotive subcategories
        {'name': 'Car & Truck Parts', 'slug': 'car-truck-parts', 'parent': 'automotive'},
        {'name': 'Motorcycle Parts', 'slug': 'motorcycle-parts', 'parent': 'automotive'},
        {'name': 'Accessories & Parts', 'slug': 'auto-accessories-parts', 'parent': 'automotive'},
        {'name': 'Tools & Equipment', 'slug': 'auto-tools-equipment', 'parent': 'automotive'},
        
        # Business & Industrial subcategories
        {'name': 'Office Equipment', 'slug': 'office-equipment', 'parent': 'business-industrial'},
        {'name': 'Industrial Equipment', 'slug': 'industrial-equipment', 'parent': 'business-industrial'},
        {'name': 'Restaurant & Food Service', 'slug': 'restaurant-food-service', 'parent': 'business-industrial'},
        {'name': 'Medical & Lab Equipment', 'slug': 'medical-lab-equipment', 'parent': 'business-industrial'},
        
        # Crafts & Supplies subcategories
        {'name': 'Beading & Jewelry Making', 'slug': 'beading-jewelry-making', 'parent': 'crafts-supplies'},
        {'name': 'Fabric & Textiles', 'slug': 'fabric-textiles', 'parent': 'crafts-supplies'},
        {'name': 'Scrapbooking & Paper Crafts', 'slug': 'scrapbooking-paper-crafts', 'parent': 'crafts-supplies'},
        {'name': 'Painting & Drawing', 'slug': 'painting-drawing', 'parent': 'crafts-supplies'},
        {'name': 'Sewing & Quilting', 'slug': 'sewing-quilting', 'parent': 'crafts-supplies'},
        {'name': 'Yarn & Needlework', 'slug': 'yarn-needlework', 'parent': 'crafts-supplies'},
        
        # Food & Beverages subcategories
        {'name': 'Gourmet Food', 'slug': 'gourmet-food', 'parent': 'food-beverages'},
        {'name': 'Beverages', 'slug': 'beverages', 'parent': 'food-beverages'},
        {'name': 'Snacks & Candy', 'slug': 'snacks-candy', 'parent': 'food-beverages'},
        {'name': 'Coffee & Tea', 'slug': 'coffee-tea', 'parent': 'food-beverages'},
    ]
    
    # Insert subcategories
    for subcat in subcategories:
        parent_id = parent_ids.get(subcat['parent'])
        if parent_id:
            connection.execute(
                sa.text("""
                    INSERT INTO categories (id, name, slug, description, parent_id, created_at)
                    VALUES (:id, :name, :slug, :description, :parent_id, NOW())
                """),
                {
                    'id': str(uuid.uuid4()),
                    'name': subcat['name'],
                    'slug': subcat['slug'],
                    'description': None,
                    'parent_id': parent_id
                }
            )


def downgrade() -> None:
    # Remove all seeded categories
    # Note: This will also remove any products associated with these categories
    # In production, you might want to set category_id to NULL instead
    connection = op.get_bind()
    connection.execute(sa.text("DELETE FROM categories"))

