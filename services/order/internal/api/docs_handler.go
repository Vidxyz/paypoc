package api

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// SwaggerUI serves the Swagger UI page
func SwaggerUI(c *gin.Context) {
	html := `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Order Service API Documentation</title>
  <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@5.9.0/swagger-ui.css" />
  <style>
    html {
      box-sizing: border-box;
      overflow: -moz-scrollbars-vertical;
      overflow-y: scroll;
    }
    *, *:before, *:after {
      box-sizing: inherit;
    }
    body {
      margin:0;
      background: #fafafa;
    }
  </style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5.9.0/swagger-ui-bundle.js"></script>
  <script src="https://unpkg.com/swagger-ui-dist@5.9.0/swagger-ui-standalone-preset.js"></script>
  <script>
    window.onload = function() {
      const ui = SwaggerUIBundle({
        url: "/api-docs/openapi.json",
        dom_id: '#swagger-ui',
        deepLinking: true,
        presets: [
          SwaggerUIBundle.presets.apis,
          SwaggerUIStandalonePreset
        ],
        plugins: [
          SwaggerUIBundle.plugins.DownloadUrl
        ],
        layout: "StandaloneLayout"
      });
    };
  </script>
</body>
</html>`
	c.Data(http.StatusOK, "text/html; charset=utf-8", []byte(html))
}

// OpenAPIJSON serves the OpenAPI JSON specification
func OpenAPIJSON(c *gin.Context) {
	spec := `{
  "openapi": "3.0.0",
  "info": {
    "title": "Order Service API",
    "version": "1.0.0",
    "description": "The Order Service manages order lifecycle from creation to fulfillment.\n\n**Key Features:**\n- Create provisional orders from cart items\n- Allocate inventory reservations\n- Integrate with Payment Service for payment creation\n- Confirm orders after payment capture\n- Generate invoices for confirmed orders\n- Track shipments per seller\n- Publish order events to Kafka\n\n**Order Lifecycle:**\n1. **Provisional Order**: Created from cart, inventory reserved, payment created\n2. **Confirmed Order**: After payment capture, inventory confirmed, shipments created\n3. **Fulfillment**: Shipments tracked through processing, shipped, delivered states\n\n**Architecture:**\n- Orders are created provisionally and confirmed after payment\n- Each seller gets a separate shipment for their items\n- Inventory reservations are soft-reserved until order confirmation\n- All order state changes are published to Kafka for downstream services",
    "contact": {
      "name": "Payments Platform Team"
    },
    "license": {
      "name": "Proprietary"
    }
  },
  "servers": [
    {
      "url": "http://localhost:8084",
      "description": "Local development"
    },
    {
      "url": "http://order.local",
      "description": "Minikube"
    }
  ],
  "paths": {
    "/health": {
      "get": {
        "summary": "Health check",
        "description": "Check if the service is healthy",
        "tags": ["Health"],
        "responses": {
          "200": {
            "description": "Service is healthy",
            "content": {
              "application/json": {
                "schema": {
                  "type": "object",
                  "properties": {
                    "status": {
                      "type": "string",
                      "example": "healthy"
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    "/internal/orders": {
      "post": {
        "summary": "Create a new order (Internal)",
        "description": "Create a provisional order from cart items. Allocates inventory, creates payment, and returns checkout URL.

**Authentication:** This endpoint requires an internal API token for service-to-service communication.

**Authorization:** Bearer token must match the configured INTERNAL_API_TOKEN.",
        "tags": ["Orders"],
        "security": [
          {
            "InternalAPIToken": []
          }
        ],
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/CreateOrderRequest"
              }
            }
          }
        },
        "responses": {
          "201": {
            "description": "Order created successfully",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/CreateOrderResponse"
                }
              }
            }
          },
          "401": {
            "description": "Unauthorized: Missing or invalid internal API token",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "400": {
            "description": "Bad request",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "500": {
            "description": "Internal server error",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          }
        }
      }
    },
    "/api/orders/{id}": {
      "get": {
        "summary": "Get order by ID",
        "description": "Retrieve order details including items and shipments.

**Authentication:** This endpoint requires a valid Auth0 JWT token.

**Authorization:**
- **BUYER**: Can only access their own orders (order.buyer_id must match JWT user_id)
- **ADMIN**: Can access any order

**JWT Claims Required:**
- https://buyit.local/user_id: User ID from Auth0
- https://buyit.local/account_type: Must be BUYER or ADMIN",
        "tags": ["Orders"],
        "security": [
          {
            "JWTAuth": []
          }
        ],
        "parameters": [
          {
            "name": "id",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "format": "uuid"
            },
            "description": "Order UUID"
          }
        ],
        "responses": {
          "200": {
            "description": "Order details",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/OrderResponse"
                }
              }
            }
          },
          "400": {
            "description": "Invalid order ID",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "401": {
            "description": "Unauthorized: Missing, invalid, or expired JWT token",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "403": {
            "description": "Forbidden: Access denied. BUYER accounts can only access their own orders.",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "404": {
            "description": "Order not found",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          }
        }
      }
    },
    "/api/orders/{id}/invoice": {
      "get": {
        "summary": "Get order invoice PDF",
        "description": "Generate and download invoice PDF for a confirmed order.

**Authentication:** This endpoint requires a valid Auth0 JWT token.

**Authorization:**
- **BUYER**: Can only access invoices for their own orders (order.buyer_id must match JWT user_id)
- **ADMIN**: Can access invoices for any order

**JWT Claims Required:**
- https://buyit.local/user_id: User ID from Auth0
- https://buyit.local/account_type: Must be BUYER or ADMIN

**Note:** Invoice is only available for orders with status CONFIRMED.",
        "tags": ["Orders"],
        "security": [
          {
            "JWTAuth": []
          }
        ],
        "parameters": [
          {
            "name": "id",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "format": "uuid"
            },
            "description": "Order UUID"
          }
        ],
        "responses": {
          "200": {
            "description": "Invoice PDF",
            "content": {
              "application/pdf": {
                "schema": {
                  "type": "string",
                  "format": "binary"
                }
              }
            }
          },
          "400": {
            "description": "Invalid order ID or order not confirmed (invoice only available for CONFIRMED orders)",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "401": {
            "description": "Unauthorized: Missing, invalid, or expired JWT token",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "403": {
            "description": "Forbidden: Access denied. BUYER accounts can only access invoices for their own orders.",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "404": {
            "description": "Order not found",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          },
          "500": {
            "description": "Failed to generate invoice",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Error"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "CreateOrderRequest": {
        "type": "object",
        "required": ["buyer_id", "cart_id", "items"],
        "properties": {
          "cart_id": {
            "type": "string",
            "format": "uuid",
            "description": "Cart UUID"
          },
          "buyer_id": {
            "type": "string",
            "description": "Buyer user ID"
          },
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/CartItem"
            },
            "description": "Cart items to convert to order"
          }
        }
      },
      "CartItem": {
        "type": "object",
        "properties": {
          "item_id": {
            "type": "string",
            "format": "uuid"
          },
          "product_id": {
            "type": "string",
            "format": "uuid"
          },
          "sku": {
            "type": "string"
          },
          "seller_id": {
            "type": "string"
          },
          "quantity": {
            "type": "integer",
            "minimum": 1
          },
          "price_cents": {
            "type": "integer",
            "description": "Price in cents"
          },
          "currency": {
            "type": "string",
            "pattern": "^[A-Z]{3}$",
            "example": "CAD"
          },
          "reservation_id": {
            "type": "string",
            "format": "uuid",
            "nullable": true
          }
        }
      },
      "CreateOrderResponse": {
        "type": "object",
        "properties": {
          "order_id": {
            "type": "string",
            "format": "uuid"
          },
          "payment_id": {
            "type": "string",
            "format": "uuid"
          },
          "client_secret": {
            "type": "string",
            "description": "Stripe client secret for payment"
          },
          "checkout_url": {
            "type": "string",
            "format": "uri"
          }
        }
      },
      "OrderResponse": {
        "type": "object",
        "properties": {
          "id": {
            "type": "string",
            "format": "uuid"
          },
          "buyer_id": {
            "type": "string"
          },
          "status": {
            "type": "string",
            "enum": ["PENDING", "CONFIRMED", "CANCELLED", "PROCESSING", "SHIPPED", "DELIVERED"]
          },
          "total_cents": {
            "type": "integer",
            "description": "Total order amount in cents"
          },
          "currency": {
            "type": "string",
            "pattern": "^[A-Z]{3}$",
            "example": "CAD"
          },
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/OrderItemResponse"
            }
          },
          "shipments": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/ShipmentResponse"
            }
          },
          "created_at": {
            "type": "string",
            "format": "date-time"
          },
          "confirmed_at": {
            "type": "string",
            "format": "date-time",
            "nullable": true
          }
        }
      },
      "OrderItemResponse": {
        "type": "object",
        "properties": {
          "id": {
            "type": "string",
            "format": "uuid"
          },
          "product_id": {
            "type": "string",
            "format": "uuid"
          },
          "sku": {
            "type": "string"
          },
          "seller_id": {
            "type": "string"
          },
          "quantity": {
            "type": "integer"
          },
          "price_cents": {
            "type": "integer"
          },
          "currency": {
            "type": "string"
          }
        }
      },
      "ShipmentResponse": {
        "type": "object",
        "properties": {
          "id": {
            "type": "string",
            "format": "uuid"
          },
          "seller_id": {
            "type": "string"
          },
          "status": {
            "type": "string",
            "enum": ["PENDING", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"]
          },
          "tracking_number": {
            "type": "string",
            "nullable": true
          },
          "carrier": {
            "type": "string",
            "nullable": true
          },
          "shipped_at": {
            "type": "string",
            "format": "date-time",
            "nullable": true
          },
          "delivered_at": {
            "type": "string",
            "format": "date-time",
            "nullable": true
          }
        }
      },
      "Error": {
        "type": "object",
        "properties": {
          "error": {
            "type": "string",
            "description": "Error message"
          }
        }
      }
    },
    "securitySchemes": {
      "InternalAPIToken": {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "opaque",
        "description": "Internal API token for service-to-service communication. Token must match the configured INTERNAL_API_TOKEN environment variable."
      },
      "JWTAuth": {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "JWT",
        "description": "Auth0 JWT token for user authentication. Token must be issued by Auth0 and include the following custom claims:
- https://buyit.local/user_id: User ID (UUID string)
- https://buyit.local/account_type: Account type (BUYER, SELLER, or ADMIN)

Token must be valid (not expired) and have the correct issuer and audience (if configured)."
      }
    }
  }
}`
	c.Data(http.StatusOK, "application/json", []byte(spec))
}
