#!/usr/bin/env python3
"""
Seed products from Amazon CSV dataset via Catalog API

This script:
1. Reads Amazon product CSV
2. Maps Amazon columns to BuyIt product schema
3. Maps Amazon categories to BuyIt categories
4. Uploads images via API
5. Creates products via API (triggers Kafka events)

Usage:
    python seed_data.py \
        --csv datasets/home/sdf/marketing_sample_for_amazon_com-ecommerce__20200101_20200131__10k_data.csv \
        --token YOUR_SELLER_AUTH_TOKEN \
        --catalog-url https://catalog.local \
        --count 500 \
        --seller-email seller1@buyit.com
"""

import csv
import random
import argparse
import requests
import time
import re
import logging
from typing import Dict, List, Optional, Any
from urllib.parse import urlparse

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


# Amazon category to BuyIt category mapping
# Maps top-level Amazon categories to our category slugs
AMAZON_TO_BUYIT_CATEGORY_MAP = {
    # Direct matches
    'Electronics': 'electronics',
    'Sports & Outdoors': 'sports-outdoors',
    'Toys & Games': 'toys-games',
    'Automotive': 'automotive',
    
    # Close matches
    'Clothing, Shoes & Jewelry': 'fashion',
    'Home & Kitchen': 'home-garden',
    'Beauty & Personal Care': 'health-beauty',
    'Industrial & Scientific': 'business-industrial',
    'Arts, Crafts & Sewing': 'crafts-supplies',
    'Grocery & Gourmet Food': 'food-beverages',
    'Movies & TV': 'books-movies-music',
    'Video Games': 'books-movies-music',  # Could also be electronics, but media fits better
    'Office Products': 'business-industrial',
    'Patio, Lawn & Garden': 'home-garden',
    'Tools & Home Improvement': 'home-garden',
    'Cell Phones & Accessories': 'electronics',
    'Hobbies': 'toys-games',  # Could be crafts-supplies, but toys is broader
    
    # Approximate matches
    'Baby Products': 'health-beauty',  # Health & Personal Care subcategory
    'Pet Supplies': 'home-garden',  # Garden & Outdoor subcategory
    'Musical Instruments': 'books-movies-music',  # Music subcategory
    'Health & Household': 'health-beauty',
    'Remote & App Controlled Vehicles & Parts': 'toys-games',
    'Remote & App Controlled Vehicle Parts': 'toys-games',
}

# Subcategory keyword mappings for better matching
SUBCATEGORY_KEYWORDS = {
    # Electronics
    'phone': 'electronics',
    'computer': 'electronics',
    'tablet': 'electronics',
    'camera': 'electronics',
    'tv': 'electronics',
    'audio': 'electronics',
    'smart home': 'electronics',
    
    # Fashion
    'clothing': 'fashion',
    'shoes': 'fashion',
    'jewelry': 'fashion',
    'watch': 'fashion',
    'handbag': 'fashion',
    'wallet': 'fashion',
    
    # Home & Garden
    'furniture': 'home-garden',
    'kitchen': 'home-garden',
    'bedding': 'home-garden',
    'bath': 'home-garden',
    'garden': 'home-garden',
    'outdoor': 'home-garden',
    'tool': 'home-garden',
    'decor': 'home-garden',
    
    # Toys & Games
    'toy': 'toys-games',
    'game': 'toys-games',
    'puzzle': 'toys-games',
    'action figure': 'toys-games',
    'doll': 'toys-games',
    'building': 'toys-games',
    'educational': 'toys-games',
    
    # Sports & Outdoors
    'sport': 'sports-outdoors',
    'fitness': 'sports-outdoors',
    'exercise': 'sports-outdoors',
    'camping': 'sports-outdoors',
    'hiking': 'sports-outdoors',
    'skateboard': 'sports-outdoors',
    'bike': 'sports-outdoors',
    
    # Health & Beauty
    'beauty': 'health-beauty',
    'skincare': 'health-beauty',
    'makeup': 'health-beauty',
    'fragrance': 'health-beauty',
    'hair': 'health-beauty',
    'vitamin': 'health-beauty',
    'supplement': 'health-beauty',
    
    # Books, Movies & Music
    'book': 'books-movies-music',
    'movie': 'books-movies-music',
    'music': 'books-movies-music',
    'dvd': 'books-movies-music',
    'cd': 'books-movies-music',
    
    # Automotive
    'car': 'automotive',
    'truck': 'automotive',
    'motorcycle': 'automotive',
    'auto': 'automotive',
    
    # Business & Industrial
    'office': 'business-industrial',
    'industrial': 'business-industrial',
    'equipment': 'business-industrial',
    
    # Crafts & Supplies
    'craft': 'crafts-supplies',
    'art': 'crafts-supplies',
    'sewing': 'crafts-supplies',
    'fabric': 'crafts-supplies',
    'yarn': 'crafts-supplies',
    
    # Food & Beverages
    'food': 'food-beverages',
    'beverage': 'food-beverages',
    'coffee': 'food-beverages',
    'tea': 'food-beverages',
    'snack': 'food-beverages',
    'candy': 'food-beverages',
}


class ProductMapper:
    """Maps Amazon CSV columns to BuyIt product schema"""
    
    def __init__(self):
        """Initialize mapper with Amazon CSV column structure"""
        # Amazon CSV columns we'll use
        self.amazon_columns = {
            'name': 'Product Name',
            'brand': 'Brand Name',
            'asin': 'Asin',
            'category': 'Category',
            'list_price': 'List Price',
            'selling_price': 'Selling Price',
            'sku': 'Sku',
            'description': 'Product Description',
            'about': 'About Product',
            'specification': 'Product Specification',
            'technical': 'Technical Details',
            'image': 'Image',
            'model': 'Model Number',
            'color': 'Color',
            'dimensions': 'Product Dimensions',
            'weight': 'Shipping Weight',
            'upc': 'Upc Ean Code',
        }
    
    def map_row(self, row: Dict[str, str], seller_email: str, product_num: int) -> Dict[str, Any]:
        """Map Amazon CSV row to BuyIt product creation payload"""
        
        def get_value(column: str, default: str = '') -> str:
            """Get value from row, handling case-insensitive matching"""
            amazon_col = self.amazon_columns.get(column)
            if amazon_col:
                # Try exact match first
                if amazon_col in row:
                    return row[amazon_col].strip() if row[amazon_col] else default
                # Try case-insensitive
                for key, value in row.items():
                    if key.lower() == amazon_col.lower():
                        return value.strip() if value else default
            return default
        
        # Name (required) - from "Product Name"
        name = get_value('name')
        if not name:
            name = f"Product {product_num}"
        # Truncate to max length
        if len(name) > 500:
            name = name[:497] + "..."
        
        # Description - combine "Product Description" and "About Product"
        description_parts = []
        desc = get_value('description')
        if desc:
            description_parts.append(desc)
        about = get_value('about')
        if about:
            # Clean up "About Product" - remove pipe separators, clean formatting
            about_clean = about.replace('|', '. ').strip()
            if about_clean and about_clean not in description_parts:
                description_parts.append(about_clean)
        
        description = ' '.join(description_parts) if description_parts else None
        if description and len(description) > 2000:
            description = description[:1997] + "..."
        
        # Price (required) - prefer "Selling Price", fallback to "List Price"
        price_str = get_value('selling_price') or get_value('list_price')
        price_cents = self._parse_price(price_str)
        if price_cents <= 0:
            # Generate reasonable default price based on category
            price_cents = random.randint(999, 99999)  # $9.99 to $999.99
        
        # Currency - default to CAD 
        currency = 'CAD'
        
        # SKU (required, unique per seller)
        # Try: Sku column, then ASIN, then generate from name
        sku = get_value('sku')
        if not sku:
            asin = get_value('asin')
            if asin:
                sku = asin
            else:
                # Generate SKU from name
                sku_base = re.sub(r'[^a-zA-Z0-9]', '', name[:20]).upper() or f"PROD{product_num:06d}"
                sku = f"{sku_base}-{product_num:06d}"
        
        # Ensure SKU is unique per seller and within length limit
        seller_prefix = seller_email.split('@')[0].upper()
        sku = f"{seller_prefix}-{sku}"[:255]
        
        # Category - will be mapped separately
        category_path = get_value('category')
        
        # Image URLs - pipe-separated in CSV
        image_urls_str = get_value('image')
        image_urls = []
        if image_urls_str:
            # Split by pipe and filter valid URLs
            for url in image_urls_str.split('|'):
                url = url.strip()
                if url and url.startswith(('http://', 'https://')):
                    # Filter out placeholder/transparent images
                    if 'transparent-pixel' not in url.lower():
                        image_urls.append(url)
        
        # Attributes (optional JSON)
        attributes = {}
        
        # Brand
        brand = get_value('brand')
        if brand:
            attributes['brand'] = brand
        
        # Model Number
        model = get_value('model')
        if model:
            attributes['model_number'] = model
        
        # Color
        color = get_value('color')
        if color:
            attributes['color'] = color
        
        # Dimensions
        dimensions = get_value('dimensions')
        if dimensions:
            attributes['dimensions'] = dimensions
        
        # Weight
        weight = get_value('weight')
        if weight:
            attributes['shipping_weight'] = weight
        
        # UPC/EAN
        upc = get_value('upc')
        if upc:
            attributes['upc_ean'] = upc
        
        # Technical details
        technical = get_value('technical')
        if technical:
            attributes['technical_details'] = technical[:500]  # Limit length
        
        return {
            'name': name,
            'description': description,
            'price_cents': price_cents,
            'currency': currency,
            'sku': sku,
            'status': 'ACTIVE',  # Make products active by default
            'category_path': category_path,  # For later mapping
            'image_urls': image_urls[:5],  # Limit to 5 images max
            'attributes': attributes if attributes else None,
        }
    
    def _parse_price(self, price_str: str) -> int:
        """Parse price string to cents"""
        if not price_str:
            return 0
        
        # Remove currency symbols and whitespace
        price_str = re.sub(r'[^\d.,]', '', str(price_str).strip())
        
        if not price_str:
            return 0
        
        # Handle different formats
        if ',' in price_str and '.' in price_str:
            # Could be "1,234.56" or "1.234,56"
            if price_str.rindex(',') > price_str.rindex('.'):
                # European format: "1.234,56"
                price_str = price_str.replace('.', '').replace(',', '.')
            else:
                # US format: "1,234.56"
                price_str = price_str.replace(',', '')
        elif ',' in price_str:
            # Could be thousands separator or decimal
            parts = price_str.split(',')
            if len(parts) == 2 and len(parts[1]) <= 2:
                # Decimal separator
                price_str = price_str.replace(',', '.')
            else:
                # Thousands separator
                price_str = price_str.replace(',', '')
        
        try:
            price_float = float(price_str)
            return int(round(price_float * 100))
        except:
            return 0


class CatalogAPIClient:
    """Client for Catalog Service API"""
    
    def __init__(self, base_url: str, auth_token: str):
        self.base_url = base_url.rstrip('/')
        self.auth_token = auth_token
        self.headers = {
            'Authorization': f'Bearer {auth_token}',
            'Content-Type': 'application/json',
        }
        self.category_cache = {}
    
    def get_categories(self) -> Dict[str, str]:
        """Get all categories and return mapping: slug -> UUID"""
        if self.category_cache:
            return self.category_cache
        
        try:
            response = requests.get(
                f'{self.base_url}/api/catalog/categories',
                headers=self.headers,
                timeout=10
            )
            response.raise_for_status()
            categories = response.json()
            
            # Build mapping: category slug/name -> UUID
            mapping = {}
            for cat in categories:
                cat_id = cat.get('id')
                name = cat.get('name', '').lower()
                slug = cat.get('slug', '').lower()
                if cat_id:
                    # Map by slug
                    mapping[slug] = cat_id
                    # Map by name
                    mapping[name] = cat_id
                    # Map by individual words (for fuzzy matching)
                    for word in name.split():
                        if len(word) > 3:
                            mapping[word] = cat_id
            
            self.category_cache = mapping
            logger.info(f"Loaded {len(categories)} categories, created {len(mapping)} mappings")
            return mapping
        except Exception as e:
            logger.warning(f"Failed to load categories: {e}. Category mapping will be skipped.")
            return {}
    
    def upload_image(self, image_url: str) -> Optional[str]:
        """Upload image from URL and return public_id"""
        if not image_url or not image_url.startswith(('http://', 'https://')):
            return None
        
        try:
            response = requests.post(
                f'{self.base_url}/api/catalog/images/upload-url',
                headers=self.headers,
                json={'url': image_url},
                timeout=30
            )
            response.raise_for_status()
            result = response.json()
            return result.get('public_id')
        except Exception as e:
            logger.warning(f"Failed to upload image {image_url[:50]}...: {e}")
            return None
    
    def create_product(self, product_data: Dict[str, Any]) -> bool:
        """Create a product"""
        try:
            response = requests.post(
                f'{self.base_url}/api/catalog/products',
                headers=self.headers,
                json=product_data,
                timeout=10
            )
            response.raise_for_status()
            return True
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 409:
                logger.warning(f"Product with SKU {product_data.get('sku')} already exists")
            else:
                logger.error(f"Failed to create product {product_data.get('name')[:50]}: {e.response.text}")
            return False
        except Exception as e:
            logger.error(f"Error creating product: {e}")
            return False


def map_amazon_category_to_buyit(amazon_category_path: str, category_mapping: Dict[str, str]) -> Optional[str]:
    """Map Amazon category path to BuyIt category UUID"""
    if not amazon_category_path:
        return None
    
    # Amazon categories are pipe-separated: "Sports & Outdoors | Outdoor Recreation | ..."
    # Get the top-level category (first part)
    top_level = amazon_category_path.split('|')[0].strip()
    
    # Try direct mapping
    buyit_slug = AMAZON_TO_BUYIT_CATEGORY_MAP.get(top_level)
    if buyit_slug and buyit_slug in category_mapping:
        return category_mapping[buyit_slug]
    
    # Try keyword matching in the full category path
    category_lower = amazon_category_path.lower()
    for keyword, slug in SUBCATEGORY_KEYWORDS.items():
        if keyword in category_lower:
            if slug in category_mapping:
                return category_mapping[slug]
    
    # Try fuzzy matching on top-level category name
    top_level_lower = top_level.lower()
    for key, cat_id in category_mapping.items():
        if top_level_lower in key or key in top_level_lower:
            return cat_id
    
    # Try word-by-word matching
    words = top_level_lower.split()
    for word in words:
        if len(word) > 3 and word in category_mapping:
            return category_mapping[word]
    
    return None


def load_csv(file_path: str) -> List[Dict[str, str]]:
    """Load CSV file and return list of rows"""
    rows = []
    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            # Try to detect delimiter
            sample = f.read(1024)
            f.seek(0)
            sniffer = csv.Sniffer()
            delimiter = sniffer.sniff(sample).delimiter
            
            reader = csv.DictReader(f, delimiter=delimiter)
            for row in reader:
                # Clean row: strip strings, handle None
                cleaned = {k: (v.strip() if v else '') for k, v in row.items()}
                rows.append(cleaned)
    except Exception as e:
        logger.error(f"Failed to load CSV: {e}")
        raise
    
    logger.info(f"Loaded {len(rows)} rows from CSV")
    return rows


def main():
    parser = argparse.ArgumentParser(
        description='Seed products from Amazon CSV dataset via Catalog API',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Example:
    python seed_data.py \\
        --csv datasets/home/sdf/marketing_sample_for_amazon_com-ecommerce__20200101_20200131__10k_data.csv \\
        --token "YOUR_SELLER_AUTH_TOKEN" \\
        --catalog-url https://catalog.local \\
        --count 500 \\
        --seller-email seller1@buyit.com
        """
    )
    parser.add_argument('--csv', required=True, help='Path to Amazon CSV file')
    parser.add_argument('--token', required=True, help='Seller auth token (JWT)')
    parser.add_argument('--catalog-url', default='https://catalog.local', help='Catalog service URL')
    parser.add_argument('--count', type=int, default=500, help='Number of products to create')
    parser.add_argument('--seller-email', required=True, help='Seller email (for SKU prefix)')
    parser.add_argument('--delay', type=float, default=0.2, help='Delay between API calls (seconds)')
    parser.add_argument('--skip-images', action='store_true', help='Skip image uploads')
    parser.add_argument('--max-images', type=int, default=3, help='Maximum images per product')
    
    args = parser.parse_args()
    
    # Load CSV
    logger.info(f"Loading CSV from {args.csv}")
    all_rows = load_csv(args.csv)
    
    if len(all_rows) == 0:
        logger.error("CSV file is empty or could not be read")
        return
    
    # Sample random rows
    sample_size = min(args.count, len(all_rows))
    sampled_rows = random.sample(all_rows, sample_size)
    logger.info(f"Sampled {sample_size} products from {len(all_rows)} total rows")
    
    # Initialize mapper
    mapper = ProductMapper()
    logger.info("Product mapper initialized with Amazon CSV column structure")
    
    # Initialize API client
    client = CatalogAPIClient(args.catalog_url, args.token)
    
    # Load categories
    logger.info("Loading BuyIt categories...")
    category_mapping = client.get_categories()
    
    if not category_mapping:
        logger.warning("No categories loaded - products will be created without categories")
    
    # Process products
    success_count = 0
    error_count = 0
    skipped_count = 0
    
    logger.info(f"\nStarting product creation (delay: {args.delay}s between calls)...\n")
    
    for i, row in enumerate(sampled_rows, 1):
        try:
            # Map row to product data
            product_data = mapper.map_row(row, args.seller_email, i)
            
            # Map category
            category_path = product_data.pop('category_path')
            category_id = map_amazon_category_to_buyit(category_path, category_mapping)
            product_data['category_id'] = category_id
            
            if not category_id:
                logger.debug(f"[{i}/{sample_size}] No category mapped for: {category_path}")
            
            # Upload images
            image_urls = product_data.pop('image_urls', [])
            images = []
            
            if image_urls and not args.skip_images:
                # Limit number of images
                for img_url in image_urls[:args.max_images]:
                    public_id = client.upload_image(img_url)
                    if public_id:
                        images.append(public_id)
                        time.sleep(args.delay)  # Rate limiting for image uploads
                    else:
                        logger.debug(f"  Skipped image: {img_url[:50]}...")
            
            product_data['images'] = images if images else None
            
            # Create product
            if client.create_product(product_data):
                success_count += 1
                logger.info(f"[{i}/{sample_size}] ✓ Created: {product_data['name'][:60]}")
            else:
                error_count += 1
                logger.warning(f"[{i}/{sample_size}] ✗ Failed: {product_data['name'][:60]}")
            
            time.sleep(args.delay)  # Rate limiting for product creation
            
        except Exception as e:
            error_count += 1
            logger.error(f"[{i}/{sample_size}] Error processing row: {e}", exc_info=True)
    
    # Summary
    logger.info(f"\n{'='*60}")
    logger.info(f"Summary")
    logger.info(f"{'='*60}")
    logger.info(f"Total processed: {sample_size}")
    logger.info(f"Success: {success_count}")
    logger.info(f"Errors: {error_count}")
    logger.info(f"Success rate: {(success_count/sample_size*100):.1f}%")
    logger.info(f"{'='*60}")


if __name__ == '__main__':
    main()

