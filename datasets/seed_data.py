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
from typing import Dict, List, Optional, Any, Tuple
from urllib.parse import urlparse
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
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
    'Hobbies': None,  # Too broad - use keyword/product name matching instead
    
    # Approximate matches
    'Baby Products': 'health-beauty',  # Health & Personal Care subcategory
    'Pet Supplies': 'home-garden',  # Garden & Outdoor subcategory
    'Musical Instruments': 'books-movies-music',  # Music subcategory
    'Health & Household': 'health-beauty',
    'Remote & App Controlled Vehicles & Parts': 'toys-games',
    'Remote & App Controlled Vehicle Parts': 'toys-games',
}

# Subcategory keyword mappings for better matching
# Order matters: more specific keywords should be checked first
# Electronics keywords are prioritized to avoid misclassification
SUBCATEGORY_KEYWORDS = {
    # Electronics (high priority - check first to avoid misclassification)
    'servo': 'electronics',
    'arduino': 'electronics',
    'raspberry pi': 'electronics',
    'circuit': 'electronics',
    'component': 'electronics',
    'resistor': 'electronics',
    'capacitor': 'electronics',
    'transistor': 'electronics',
    'led': 'electronics',
    'sensor': 'electronics',
    'microcontroller': 'electronics',
    'breadboard': 'electronics',
    'extension': 'electronics',  # Cable/connector extensions
    'cable': 'electronics',
    'connector': 'electronics',
    'adapter': 'electronics',
    'charger': 'electronics',
    'battery': 'electronics',
    'phone': 'electronics',
    'smartphone': 'electronics',
    'computer': 'electronics',
    'laptop': 'electronics',
    'tablet': 'electronics',
    'camera': 'electronics',
    'tv': 'electronics',
    'television': 'electronics',
    'audio': 'electronics',
    'speaker': 'electronics',
    'headphone': 'electronics',
    'smart home': 'electronics',
    'router': 'electronics',
    'modem': 'electronics',
    'wireless': 'electronics',
    'bluetooth': 'electronics',
    'usb': 'electronics',
    'hdmi': 'electronics',
    
    # Business & Industrial (check before toys to catch RC parts correctly)
    'industrial': 'business-industrial',
    'equipment': 'business-industrial',
    'machinery': 'business-industrial',
    'professional': 'business-industrial',
    'commercial': 'business-industrial',
    'office': 'business-industrial',
    
    # Fashion
    'clothing': 'fashion',
    'apparel': 'fashion',
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
    
    # Toys & Games (lower priority - only match if clearly toys)
    'toy': 'toys-games',
    'action figure': 'toys-games',
    'doll': 'toys-games',
    'building block': 'toys-games',
    'lego': 'toys-games',
    'puzzle': 'toys-games',
    'board game': 'toys-games',
    'card game': 'toys-games',
    # Note: 'game' alone is too broad, so we don't include it
    # 'educational' is also too broad
    
    # Sports & Outdoors
    'sport': 'sports-outdoors',
    'fitness': 'sports-outdoors',
    'exercise': 'sports-outdoors',
    'camping': 'sports-outdoors',
    'hiking': 'sports-outdoors',
    'skateboard': 'sports-outdoors',
    'bike': 'sports-outdoors',
    'bicycle': 'sports-outdoors',
    
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
    'vehicle': 'automotive',
    
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
            logger.info(f"Using cached categories ({len(self.category_cache)} mappings)")
            return self.category_cache
        
        logger.info(f"Fetching categories from {self.base_url}/api/catalog/categories...")
        try:
            start_time = time.time()
            response = requests.get(
                f'{self.base_url}/api/catalog/categories',
                headers=self.headers,
                timeout=10
            )
            response.raise_for_status()
            categories = response.json()
            elapsed = time.time() - start_time
            
            logger.info(f"✓ Received {len(categories)} categories in {elapsed:.2f}s")
            
            # Build mapping: category slug/name -> UUID
            logger.info("Building category mapping...")
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
            logger.info(f"✓ Created {len(mapping)} category mappings from {len(categories)} categories")
            return mapping
        except requests.exceptions.HTTPError as e:
            elapsed = time.time() - start_time if 'start_time' in locals() else 0
            response_text = ""
            if hasattr(e, 'response') and e.response is not None:
                try:
                    response_text = e.response.text
                except:
                    response_text = f"Could not read response text: {str(e)}"
            logger.error(f"✗ HTTP {e.response.status_code if hasattr(e, 'response') and e.response else 'unknown'} error loading categories ({elapsed:.2f}s)")
            logger.error(f"  Response content: {response_text}")
            return {}
        except Exception as e:
            logger.warning(f"✗ Failed to load categories: {e}. Category mapping will be skipped.")
            return {}
    
    def upload_image(self, image_url: str, image_num: int = 0, total_images: int = 0) -> Optional[str]:
        """Upload image from URL and return public_id"""
        if not image_url or not image_url.startswith(('http://', 'https://')):
            return None
        
        try:
            start_time = time.time()
            response = requests.post(
                f'{self.base_url}/api/catalog/images/upload-url',
                headers=self.headers,
                json={'url': image_url},
                timeout=30
            )
            response.raise_for_status()
            result = response.json()
            public_id = result.get('public_id')
            elapsed = time.time() - start_time
            
            if total_images > 0:
                logger.info(f"    ↳ Image {image_num}/{total_images}: Uploaded {public_id[:30]}... ({elapsed:.2f}s)")
            else:
                logger.debug(f"    ↳ Uploaded image: {public_id[:30]}... ({elapsed:.2f}s)")
            return public_id
        except requests.exceptions.HTTPError as e:
            elapsed = time.time() - start_time
            response_text = ""
            if hasattr(e, 'response') and e.response is not None:
                try:
                    response_text = e.response.text
                except:
                    response_text = f"Could not read response text: {str(e)}"
            logger.warning(f"    ↳ Image {image_num}/{total_images}: HTTP {e.response.status_code if hasattr(e, 'response') and e.response else 'unknown'} error uploading {image_url[:50]}... ({elapsed:.2f}s)")
            logger.warning(f"      Response content: {response_text}")
            return None
        except requests.exceptions.Timeout:
            elapsed = time.time() - start_time if 'start_time' in locals() else 0
            logger.warning(f"    ↳ Image {image_num}/{total_images}: Timeout uploading {image_url[:50]}... ({elapsed:.2f}s)")
            return None
        except Exception as e:
            elapsed = time.time() - start_time if 'start_time' in locals() else 0
            logger.warning(f"    ↳ Image {image_num}/{total_images}: Failed to upload {image_url[:50]}...: {str(e)} ({elapsed:.2f}s)")
            return None
    
    def create_product(self, product_data: Dict[str, Any], product_num: int = 0, total: int = 0) -> Optional[str]:
        """Create a product and return product ID"""
        start_time = time.time()
        try:
            response = requests.post(
                f'{self.base_url}/api/catalog/products',
                headers=self.headers,
                json=product_data,
                timeout=10
            )
            response.raise_for_status()
            elapsed = time.time() - start_time
            
            result = response.json()
            product_id = result.get('id')
            if product_id:
                if total > 0:
                    logger.info(f"  ✓ Product created: ID={product_id[:8]}... ({elapsed:.2f}s)")
                return product_id
            else:
                logger.warning(f"  ⚠ Product created but no ID returned ({elapsed:.2f}s)")
                return None
        except requests.exceptions.HTTPError as e:
            elapsed = time.time() - start_time
            response_text = ""
            if hasattr(e, 'response') and e.response is not None:
                try:
                    response_text = e.response.text
                except:
                    response_text = f"Could not read response text: {str(e)}"
            
            if e.response.status_code == 409:
                logger.warning(f"  ⚠ Product with SKU {product_data.get('sku')} already exists (409 Conflict) ({elapsed:.2f}s)")
                if response_text:
                    logger.debug(f"    Response content: {response_text}")
            else:
                logger.error(f"  ✗ HTTP {e.response.status_code}: Unexpected status code ({elapsed:.2f}s)")
                logger.error(f"    Response content: {response_text}")
            return None
        except requests.exceptions.Timeout:
            elapsed = time.time() - start_time
            logger.error(f"  ✗ Timeout creating product (exceeded 10s) ({elapsed:.2f}s)")
            return None
        except Exception as e:
            elapsed = time.time() - start_time
            logger.error(f"  ✗ Error creating product: {str(e)[:200]} ({elapsed:.2f}s)")
            return None


class InventoryAPIClient:
    """Client for Inventory Service API"""
    
    def __init__(self, base_url: str, auth_token: str):
        self.base_url = base_url.rstrip('/')
        self.auth_token = auth_token
        self.headers = {
            'Authorization': f'Bearer {auth_token}',
            'Content-Type': 'application/json',
        }
    
    def create_or_update_stock(self, product_id: str, sku: str, quantity: int, product_num: int = 0, total: int = 0) -> bool:
        """Create or update inventory stock for a product"""
        start_time = time.time()
        try:
            response = requests.put(
                f'{self.base_url}/api/inventory/stock/{product_id}',
                headers=self.headers,
                json={
                    'sku': sku,
                    'quantity': quantity
                },
                timeout=10
            )
            response.raise_for_status()
            elapsed = time.time() - start_time
            
            if total > 0:
                logger.info(f"  ✓ Inventory created: quantity={quantity} ({elapsed:.2f}s)")
            return True
        except requests.exceptions.HTTPError as e:
            elapsed = time.time() - start_time
            response_text = ""
            if hasattr(e, 'response') and e.response is not None:
                try:
                    response_text = e.response.text
                except:
                    response_text = f"Could not read response text: {str(e)}"
            
            logger.warning(f"  ⚠ Failed to create inventory: HTTP {e.response.status_code if hasattr(e, 'response') and e.response else 'unknown'} ({elapsed:.2f}s)")
            logger.warning(f"    Response content: {response_text}")
            return False
        except requests.exceptions.Timeout:
            elapsed = time.time() - start_time
            logger.warning(f"  ⚠ Timeout creating inventory (exceeded 10s) ({elapsed:.2f}s)")
            return False
        except Exception as e:
            elapsed = time.time() - start_time
            logger.warning(f"  ⚠ Error creating inventory: {str(e)[:200]} ({elapsed:.2f}s)")
            return False


def map_amazon_category_to_buyit(
    amazon_category_path: str, 
    category_mapping: Dict[str, str],
    product_name: Optional[str] = None,
    product_description: Optional[str] = None
) -> Optional[str]:
    """Map Amazon category path to BuyIt category UUID
    
    Uses multiple strategies:
    1. Direct category mapping
    2. Product name keyword analysis (most reliable for electronics components)
    3. Category path keyword matching
    4. Fuzzy matching
    
    Args:
        amazon_category_path: Amazon category path (pipe-separated)
        category_mapping: Mapping of BuyIt category slugs to UUIDs
        product_name: Product name for keyword analysis
        product_description: Product description for keyword analysis
    """
    if not amazon_category_path:
        return None
    
    # Amazon categories are pipe-separated: "Sports & Outdoors | Outdoor Recreation | ..."
    # Get the top-level category (first part)
    top_level = amazon_category_path.split('|')[0].strip()
    
    # Strategy 1: Try direct mapping
    buyit_slug = AMAZON_TO_BUYIT_CATEGORY_MAP.get(top_level)
    if buyit_slug and buyit_slug in category_mapping:
        return category_mapping[buyit_slug]
    
    # Strategy 2: Analyze product name/description for electronics keywords (high priority)
    # This catches cases like "Servo Extension" that might be miscategorized
    if product_name or product_description:
        search_text = ' '.join([
            (product_name or '').lower(),
            (product_description or '').lower()
        ])
        
        # Check for electronics keywords first (they take priority)
        for keyword, slug in SUBCATEGORY_KEYWORDS.items():
            if slug == 'electronics' and keyword in search_text:
                if slug in category_mapping:
                    return category_mapping[slug]
    
    # Strategy 3: Try keyword matching in the full category path
    category_lower = amazon_category_path.lower()
    for keyword, slug in SUBCATEGORY_KEYWORDS.items():
        if slug and keyword in category_lower:
            if slug in category_mapping:
                return category_mapping[slug]
    
    # Strategy 4: Try keyword matching in product name/description (non-electronics)
    if product_name or product_description:
        search_text = ' '.join([
            (product_name or '').lower(),
            (product_description or '').lower()
        ])
        
        for keyword, slug in SUBCATEGORY_KEYWORDS.items():
            if slug and slug != 'electronics' and keyword in search_text:
                if slug in category_mapping:
                    return category_mapping[slug]
    
    # Strategy 5: Try fuzzy matching on top-level category name
    top_level_lower = top_level.lower()
    for key, cat_id in category_mapping.items():
        if top_level_lower in key or key in top_level_lower:
            return cat_id
    
    # Strategy 6: Try word-by-word matching
    words = top_level_lower.split()
    for word in words:
        if len(word) > 3 and word in category_mapping:
            return category_mapping[word]
    
    return None


def load_csv(file_path: str) -> List[Dict[str, str]]:
    """Load CSV file and return list of rows"""
    logger.info(f"Reading CSV file: {file_path}")
    start_time = time.time()
    rows = []
    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            # Try to detect delimiter
            logger.debug("Detecting CSV delimiter...")
            sample = f.read(1024)
            f.seek(0)
            sniffer = csv.Sniffer()
            delimiter = sniffer.sniff(sample).delimiter
            logger.debug(f"Detected delimiter: '{delimiter}'")
            
            reader = csv.DictReader(f, delimiter=delimiter)
            logger.info("Reading CSV rows...")
            
            row_count = 0
            for row in reader:
                # Clean row: strip strings, handle None
                cleaned = {k: (v.strip() if v else '') for k, v in row.items()}
                rows.append(cleaned)
                row_count += 1
                
                # Log progress every 1000 rows
                if row_count % 1000 == 0:
                    logger.info(f"  Read {row_count:,} rows...")
            
            elapsed = time.time() - start_time
            logger.info(f"✓ Loaded {len(rows):,} rows from CSV in {elapsed:.2f}s")
            
            # Log column names
            if rows:
                columns = list(rows[0].keys())
                logger.info(f"  CSV columns ({len(columns)}): {', '.join(columns[:10])}{'...' if len(columns) > 10 else ''}")
            
    except FileNotFoundError:
        logger.error(f"✗ CSV file not found: {file_path}")
        raise
    except Exception as e:
        logger.error(f"✗ Failed to load CSV: {e}")
        raise
    
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
    parser.add_argument('--inventory-url', default='https://inventory.local', help='Inventory service URL')
    parser.add_argument('--count', type=int, default=500, help='Number of products to create')
    parser.add_argument('--seller-email', required=True, help='Seller email (for SKU prefix)')
    parser.add_argument('--delay', type=float, default=0.2, help='Delay between API calls (seconds)')
    parser.add_argument('--skip-images', action='store_true', help='Skip image uploads')
    parser.add_argument('--max-images', type=int, default=3, help='Maximum images per product')
    parser.add_argument('--max-parallel-images', type=int, default=5, help='Maximum parallel image uploads (default: 5)')
    parser.add_argument('--inventory-quantity', type=int, default=10, help='Inventory quantity to set for each product')
    parser.add_argument('--skip-inventory', action='store_true', help='Skip inventory creation')
    
    args = parser.parse_args()
    
    # Start timing
    script_start_time = time.time()
    logger.info("="*70)
    logger.info("BUYIT PRODUCT SEEDING SCRIPT")
    logger.info("="*70)
    logger.info(f"Started at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    logger.info(f"Configuration:")
    logger.info(f"  CSV file: {args.csv}")
    logger.info(f"  Catalog URL: {args.catalog_url}")
    logger.info(f"  Inventory URL: {args.inventory_url}")
    logger.info(f"  Seller email: {args.seller_email}")
    logger.info(f"  Target count: {args.count}")
    logger.info(f"  Delay between calls: {args.delay}s")
    logger.info(f"  Skip images: {args.skip_images}")
    logger.info(f"  Max images per product: {args.max_images}")
    logger.info(f"  Max parallel image uploads: {args.max_parallel_images}")
    logger.info(f"  Inventory quantity: {args.inventory_quantity}")
    logger.info(f"  Skip inventory: {args.skip_inventory}")
    logger.info("="*70)
    
    # Load CSV
    logger.info("\n[STEP 1/5] Loading CSV file...")
    all_rows = load_csv(args.csv)
    
    if len(all_rows) == 0:
        logger.error("✗ CSV file is empty or could not be read")
        return
    
    # Shuffle and sample random rows to ensure category diversity
    logger.info("\n[STEP 2/5] Sampling products...")
    logger.info("  Shuffling dataset to ensure category diversity...")
    
    # Create a copy to avoid modifying the original
    shuffled_rows = all_rows.copy()

    # Initialize mapper
    logger.info("\n[STEP 3/5] Initializing components...")
    mapper = ProductMapper()
    logger.info("✓ Product mapper initialized")
    
    # Shuffle multiple times to ensure better randomization
    # This helps break up any category clustering in the CSV
    for shuffle_round in range(3):
        random.shuffle(shuffled_rows)
        logger.debug(f"  Shuffle round {shuffle_round + 1}/3 completed")
    
    # Now sample from the shuffled list
    sample_size = min(args.count, len(shuffled_rows))
    sampled_rows = random.sample(shuffled_rows, sample_size)
    
    # Log category distribution for verification
    if sampled_rows:
        category_column = mapper.amazon_columns.get('category', 'Category')
        sampled_categories = {}
        for row in sampled_rows:
            category = row.get(category_column, '').strip()
            if category:
                # Get top-level category (first part before |)
                top_level = category.split('|')[0].strip()
                sampled_categories[top_level] = sampled_categories.get(top_level, 0) + 1
        
        logger.info(f"✓ Sampled {sample_size:,} products from {len(all_rows):,} total rows")
        logger.info(f"  Category distribution: {len(sampled_categories)} unique top-level categories")
        if len(sampled_categories) > 0:
            top_categories = sorted(sampled_categories.items(), key=lambda x: x[1], reverse=True)[:5]
            logger.info(f"  Top categories: {', '.join([f'{cat}({count})' for cat, count in top_categories])}")
    else:
        logger.info(f"✓ Sampled {sample_size:,} products from {len(all_rows):,} total rows")
    
    
    
    # Initialize API clients
    catalog_client = CatalogAPIClient(args.catalog_url, args.token)
    logger.info(f"✓ Catalog API client initialized for {args.catalog_url}")
    
    inventory_client = None
    if not args.skip_inventory:
        inventory_client = InventoryAPIClient(args.inventory_url, args.token)
        logger.info(f"✓ Inventory API client initialized for {args.inventory_url}")
    else:
        logger.info("⚠ Inventory creation skipped (--skip-inventory flag)")
    
    # Load categories
    logger.info("\n[STEP 4/5] Loading BuyIt categories...")
    category_mapping = catalog_client.get_categories()
    
    if not category_mapping:
        logger.warning("⚠ No categories loaded - products will be created without categories")
    else:
        logger.info(f"✓ Category mapping ready ({len(category_mapping)} mappings)")
    
    # Process products
    logger.info("\n[STEP 5/5] Processing products...")
    logger.info("="*70)
    
    success_count = 0
    error_count = 0
    skipped_count = 0
    total_images_uploaded = 0
    total_images_failed = 0
    categories_mapped = 0
    categories_unmapped = 0
    inventory_created = 0
    inventory_failed = 0
    
    process_start_time = time.time()
    
    for i, row in enumerate(sampled_rows, 1):
        try:
            # Progress header
            progress_pct = (i / sample_size) * 100
            elapsed = time.time() - process_start_time
            avg_time_per_item = elapsed / i if i > 0 else 0
            remaining = (sample_size - i) * avg_time_per_item
            
            logger.info(f"\n[{i}/{sample_size}] ({progress_pct:.1f}%) Processing product...")
            logger.info(f"  ETA: {remaining/60:.1f} minutes | Avg: {avg_time_per_item:.2f}s/item")
            
            # Map row to product data
            logger.debug(f"  Mapping CSV row to product schema...")
            product_data = mapper.map_row(row, args.seller_email, i)
            logger.info(f"  → Name: {product_data['name'][:60]}")
            logger.info(f"  → SKU: {product_data['sku']}")
            logger.info(f"  → Price: {product_data['price_cents']/100:.2f} {product_data['currency']}")
            
            # Map category (use product name and description for better accuracy)
            category_path = product_data.pop('category_path')
            product_name = product_data.get('name')
            product_description = product_data.get('description')
            category_id = map_amazon_category_to_buyit(
                category_path, 
                category_mapping,
                product_name=product_name,
                product_description=product_description
            )
            product_data['category_id'] = category_id
            
            if category_id:
                categories_mapped += 1
                logger.info(f"  → Category: {category_path[:60]} → Mapped")
            else:
                categories_unmapped += 1
                if category_path:
                    logger.warning(f"  → Category: {category_path[:60]} → No mapping found")
                else:
                    logger.debug(f"  → Category: (empty)")
            
            # Upload images in parallel
            image_urls = product_data.pop('image_urls', [])
            images = []
            
            if image_urls and not args.skip_images:
                total_to_upload = min(len(image_urls), args.max_images)
                images_to_upload = image_urls[:args.max_images]
                logger.info(f"  → Images: Uploading {total_to_upload} of {len(image_urls)} available (parallel: {min(args.max_parallel_images, total_to_upload)})...")
                
                upload_start_time = time.time()
                
                # Upload images in parallel using ThreadPoolExecutor
                with ThreadPoolExecutor(max_workers=min(args.max_parallel_images, total_to_upload)) as executor:
                    # Submit all upload tasks
                    future_to_image = {
                        executor.submit(catalog_client.upload_image, img_url, img_idx + 1, total_to_upload): (img_idx, img_url)
                        for img_idx, img_url in enumerate(images_to_upload)
                    }
                    
                    # Collect results as they complete
                    for future in as_completed(future_to_image):
                        img_idx, img_url = future_to_image[future]
                        try:
                            public_id = future.result()
                            if public_id:
                                images.append(public_id)
                                total_images_uploaded += 1
                            else:
                                total_images_failed += 1
                        except Exception as e:
                            total_images_failed += 1
                            logger.warning(f"    ↳ Image {img_idx + 1}/{total_to_upload}: Exception during upload: {str(e)[:100]}")
                
                upload_elapsed = time.time() - upload_start_time
                
                if images:
                    logger.info(f"  ✓ Uploaded {len(images)}/{total_to_upload} images in {upload_elapsed:.2f}s (parallel)")
                else:
                    logger.warning(f"  ⚠ No images uploaded ({total_images_failed} failed)")
            elif args.skip_images:
                logger.info(f"  → Images: Skipped (--skip-images flag)")
            else:
                logger.info(f"  → Images: None available")
            
            product_data['images'] = images if images else None
            
            # Create product
            logger.info(f"  Creating product via API...")
            product_id = catalog_client.create_product(product_data, i, sample_size)
            
            if product_id:
                success_count += 1
                logger.info(f"  ✓ Product created successfully!")
                
                # Create inventory for the product
                if inventory_client and not args.skip_inventory:
                    logger.info(f"  Creating inventory (quantity={args.inventory_quantity})...")
                    if inventory_client.create_or_update_stock(
                        product_id=product_id,
                        sku=product_data['sku'],
                        quantity=args.inventory_quantity,
                        product_num=i,
                        total=sample_size
                    ):
                        inventory_created += 1
                        logger.info(f"  ✓ Inventory created successfully!")
                    else:
                        inventory_failed += 1
                        logger.warning(f"  ⚠ Inventory creation failed (product still created)")
                    time.sleep(args.delay)  # Rate limiting for inventory creation
            else:
                error_count += 1
                logger.warning(f"  ✗ Product creation failed")
            
            time.sleep(args.delay)  # Rate limiting for product creation
            
        except KeyboardInterrupt:
            logger.warning(f"\n\n⚠ Interrupted by user at product {i}/{sample_size}")
            break
        except Exception as e:
            error_count += 1
            logger.error(f"  ✗ Error processing row: {e}", exc_info=True)
    
    # Summary
    total_time = time.time() - script_start_time
    process_time = time.time() - process_start_time
    
    logger.info(f"\n\n{'='*70}")
    logger.info("SEEDING SUMMARY")
    logger.info(f"{'='*70}")
    logger.info(f"Total time: {total_time/60:.2f} minutes ({total_time:.1f}s)")
    logger.info(f"Processing time: {process_time/60:.2f} minutes ({process_time:.1f}s)")
    logger.info(f"")
    logger.info(f"Products:")
    logger.info(f"  Total processed: {sample_size:,}")
    logger.info(f"  ✓ Success: {success_count:,}")
    logger.info(f"  ✗ Errors: {error_count:,}")
    logger.info(f"  Success rate: {(success_count/sample_size*100):.1f}%")
    logger.info(f"")
    logger.info(f"Categories:")
    logger.info(f"  ✓ Mapped: {categories_mapped:,}")
    logger.info(f"  ⚠ Unmapped: {categories_unmapped:,}")
    logger.info(f"")
    if not args.skip_images:
        logger.info(f"Images:")
        logger.info(f"  ✓ Uploaded: {total_images_uploaded:,}")
        logger.info(f"  ✗ Failed: {total_images_failed:,}")
        logger.info(f"")
    if not args.skip_inventory:
        logger.info(f"Inventory:")
        logger.info(f"  ✓ Created: {inventory_created:,}")
        logger.info(f"  ✗ Failed: {inventory_failed:,}")
        logger.info(f"")
    logger.info(f"Average time per product: {process_time/sample_size:.2f}s")
    logger.info(f"{'='*70}")
    logger.info(f"Completed at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    logger.info(f"{'='*70}\n")


if __name__ == '__main__':
    main()

