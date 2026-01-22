package repository

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/payments-platform/order-service/internal/models"
)

type OrderRepository struct {
	pool *pgxpool.Pool
}

func NewOrderRepository(pool *pgxpool.Pool) *OrderRepository {
	return &OrderRepository{pool: pool}
}

func (r *OrderRepository) Create(ctx context.Context, order *models.Order) error {
	_, err := r.pool.Exec(ctx,
		`INSERT INTO orders (id, cart_id, buyer_id, status, provisional, payment_id, total_cents, currency, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		order.ID, order.CartID, order.BuyerID, order.Status, order.Provisional, order.PaymentID,
		order.TotalCents, order.Currency, order.CreatedAt, order.UpdatedAt,
	)
	return err
}

func (r *OrderRepository) CreateItems(ctx context.Context, items []models.OrderItem) error {
	for _, item := range items {
		_, err := r.pool.Exec(ctx,
			`INSERT INTO order_items (id, order_id, product_id, sku, seller_id, quantity, price_cents, currency, reservation_id, shipment_id, created_at)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
			item.ID, item.OrderID, item.ProductID, item.SKU, item.SellerID,
			item.Quantity, item.PriceCents, item.Currency, item.ReservationID, item.ShipmentID, item.CreatedAt,
		)
		if err != nil {
			return fmt.Errorf("failed to create order item: %w", err)
		}
	}
	return nil
}

func (r *OrderRepository) CreateShipments(ctx context.Context, shipments []models.Shipment) error {
	for _, shipment := range shipments {
		_, err := r.pool.Exec(ctx,
			`INSERT INTO shipments (id, order_id, seller_id, status, provisional, created_at, updated_at)
			 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
			shipment.ID, shipment.OrderID, shipment.SellerID, shipment.Status,
			shipment.Provisional, shipment.CreatedAt, shipment.UpdatedAt,
		)
		if err != nil {
			return fmt.Errorf("failed to create shipment: %w", err)
		}
	}
	return nil
}

func (r *OrderRepository) GetByID(ctx context.Context, id uuid.UUID) (*models.Order, error) {
	var order models.Order
	var confirmedAt, cancelledAt *time.Time
	var refundStatus string

	var cartID *uuid.UUID
	err := r.pool.QueryRow(ctx,
		`SELECT id, cart_id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
		        created_at, updated_at, confirmed_at, cancelled_at
		 FROM orders WHERE id = $1`,
		id,
	).Scan(
		&order.ID, &cartID, &order.BuyerID, &order.Status, &order.Provisional, &order.PaymentID,
		&order.TotalCents, &order.Currency, &refundStatus, &order.CreatedAt, &order.UpdatedAt,
		&confirmedAt, &cancelledAt,
	)
	order.CartID = cartID
	if err != nil {
		return nil, err
	}

	order.ConfirmedAt = confirmedAt
	order.CancelledAt = cancelledAt
	order.RefundStatus = refundStatus
	return &order, nil
}

func (r *OrderRepository) GetItemsByOrderID(ctx context.Context, orderID uuid.UUID) ([]models.OrderItem, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT id, order_id, product_id, sku, seller_id, quantity, price_cents, currency, refunded_quantity, reservation_id, shipment_id, created_at
		 FROM order_items WHERE order_id = $1`,
		orderID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []models.OrderItem
	for rows.Next() {
		var item models.OrderItem
		err := rows.Scan(
			&item.ID, &item.OrderID, &item.ProductID, &item.SKU, &item.SellerID,
			&item.Quantity, &item.PriceCents, &item.Currency, &item.RefundedQuantity,
			&item.ReservationID, &item.ShipmentID, &item.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	return items, rows.Err()
}

func (r *OrderRepository) GetShipmentsByOrderID(ctx context.Context, orderID uuid.UUID) ([]models.Shipment, error) {
	// Log the query for debugging
	log.Printf("[GetShipmentsByOrderID] Querying shipments for order_id: %s", orderID)

	rows, err := r.pool.Query(ctx,
		`SELECT id, order_id, seller_id, status, provisional, tracking_number, carrier, 
		        shipped_at, delivered_at, created_at, updated_at
		 FROM shipments WHERE order_id = $1`,
		orderID,
	)
	if err != nil {
		log.Printf("[GetShipmentsByOrderID] Database query error for order_id %s: %v", orderID, err)
		return nil, err
	}
	defer rows.Close()

	var shipments []models.Shipment
	for rows.Next() {
		var shipment models.Shipment
		err := rows.Scan(
			&shipment.ID, &shipment.OrderID, &shipment.SellerID, &shipment.Status, &shipment.Provisional,
			&shipment.TrackingNumber, &shipment.Carrier, &shipment.ShippedAt, &shipment.DeliveredAt,
			&shipment.CreatedAt, &shipment.UpdatedAt,
		)
		if err != nil {
			log.Printf("[GetShipmentsByOrderID] Row scan error for order_id %s: %v", orderID, err)
			return nil, err
		}
		shipments = append(shipments, shipment)
	}

	log.Printf("[GetShipmentsByOrderID] Found %d shipments for order_id %s", len(shipments), orderID)
	return shipments, rows.Err()
}

func (r *OrderRepository) GetByPaymentID(ctx context.Context, paymentID uuid.UUID) (*models.Order, error) {
	var order models.Order
	var confirmedAt, cancelledAt *time.Time
	var refundStatus string
	var cartID *uuid.UUID

	err := r.pool.QueryRow(ctx,
		`SELECT id, cart_id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
		        created_at, updated_at, confirmed_at, cancelled_at
		 FROM orders WHERE payment_id = $1`,
		paymentID,
	).Scan(
		&order.ID, &cartID, &order.BuyerID, &order.Status, &order.Provisional, &order.PaymentID,
		&order.TotalCents, &order.Currency, &refundStatus, &order.CreatedAt, &order.UpdatedAt,
		&confirmedAt, &cancelledAt,
	)
	if err != nil {
		return nil, err
	}

	order.CartID = cartID
	order.ConfirmedAt = confirmedAt
	order.CancelledAt = cancelledAt
	order.RefundStatus = refundStatus
	return &order, nil
}

func (r *OrderRepository) ConfirmOrder(ctx context.Context, orderID uuid.UUID, confirmedAt time.Time) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE orders SET status = $1, provisional = $2, confirmed_at = $3, updated_at = $4 WHERE id = $5`,
		models.OrderStatusConfirmed, false, confirmedAt, time.Now(), orderID,
	)
	return err
}

func (r *OrderRepository) UpdateShipmentProvisional(ctx context.Context, orderID uuid.UUID, provisional bool) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE shipments SET provisional = $1, updated_at = $2 WHERE order_id = $3`,
		provisional, time.Now(), orderID,
	)
	return err
}

// GetShipmentByID retrieves a shipment by ID
func (r *OrderRepository) GetShipmentByID(ctx context.Context, shipmentID uuid.UUID) (*models.Shipment, error) {
	var shipment models.Shipment
	err := r.pool.QueryRow(ctx,
		`SELECT id, order_id, seller_id, status, provisional, tracking_number, carrier, 
		        shipped_at, delivered_at, created_at, updated_at
		 FROM shipments WHERE id = $1`,
		shipmentID,
	).Scan(
		&shipment.ID, &shipment.OrderID, &shipment.SellerID, &shipment.Status, &shipment.Provisional,
		&shipment.TrackingNumber, &shipment.Carrier, &shipment.ShippedAt, &shipment.DeliveredAt,
		&shipment.CreatedAt, &shipment.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &shipment, nil
}

// UpdateShipmentStatus updates the status and timestamps of a shipment
func (r *OrderRepository) UpdateShipmentStatus(ctx context.Context, shipmentID uuid.UUID, status string, shippedAt, deliveredAt *time.Time) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE shipments SET status = $1, shipped_at = $2, delivered_at = $3, updated_at = $4 WHERE id = $5`,
		status, shippedAt, deliveredAt, time.Now(), shipmentID,
	)
	return err
}

// UpdateShipmentTracking updates the tracking number and carrier of a shipment
func (r *OrderRepository) UpdateShipmentTracking(ctx context.Context, shipmentID uuid.UUID, trackingNumber, carrier string) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE shipments SET tracking_number = $1, carrier = $2, updated_at = $3 WHERE id = $4`,
		trackingNumber, carrier, time.Now(), shipmentID,
	)
	return err
}

// UpdateOrderStatus updates the status of an order
func (r *OrderRepository) UpdateOrderStatus(ctx context.Context, orderID uuid.UUID, status models.OrderStatus) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE orders SET status = $1, updated_at = $2 WHERE id = $3`,
		status, time.Now(), orderID,
	)
	return err
}

// GetShipmentsBySellerID retrieves all shipments for a seller
func (r *OrderRepository) GetShipmentsBySellerID(ctx context.Context, sellerID string, limit, offset int) ([]models.Shipment, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT id, order_id, seller_id, status, provisional, tracking_number, carrier, 
		        shipped_at, delivered_at, created_at, updated_at
		 FROM shipments 
		 WHERE seller_id = $1
		 ORDER BY created_at DESC
		 LIMIT $2 OFFSET $3`,
		sellerID, limit, offset,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var shipments []models.Shipment
	for rows.Next() {
		var shipment models.Shipment
		err := rows.Scan(
			&shipment.ID, &shipment.OrderID, &shipment.SellerID, &shipment.Status, &shipment.Provisional,
			&shipment.TrackingNumber, &shipment.Carrier, &shipment.ShippedAt, &shipment.DeliveredAt,
			&shipment.CreatedAt, &shipment.UpdatedAt,
		)
		if err != nil {
			return nil, err
		}
		shipments = append(shipments, shipment)
	}

	return shipments, rows.Err()
}

// GetShipmentByTrackingNumber retrieves a shipment by tracking number
func (r *OrderRepository) GetShipmentByTrackingNumber(ctx context.Context, trackingNumber string) (*models.Shipment, error) {
	var shipment models.Shipment
	err := r.pool.QueryRow(ctx,
		`SELECT id, order_id, seller_id, status, provisional, tracking_number, carrier, 
		        shipped_at, delivered_at, created_at, updated_at
		 FROM shipments WHERE tracking_number = $1`,
		trackingNumber,
	).Scan(
		&shipment.ID, &shipment.OrderID, &shipment.SellerID, &shipment.Status, &shipment.Provisional,
		&shipment.TrackingNumber, &shipment.Carrier, &shipment.ShippedAt, &shipment.DeliveredAt,
		&shipment.CreatedAt, &shipment.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &shipment, nil
}

func (r *OrderRepository) CancelOrder(ctx context.Context, orderID uuid.UUID, cancelledAt time.Time) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE orders SET status = $1, cancelled_at = $2, updated_at = $3 WHERE id = $4`,
		models.OrderStatusCancelled, cancelledAt, time.Now(), orderID,
	)
	return err
}

// UpdatePaymentID updates the payment_id for an order
func (r *OrderRepository) UpdatePaymentID(ctx context.Context, orderID uuid.UUID, paymentID uuid.UUID) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE orders SET payment_id = $1, updated_at = $2 WHERE id = $3`,
		paymentID, time.Now(), orderID,
	)
	return err
}

// UpdateItemRefundedQuantity updates the refunded quantity for an order item
func (r *OrderRepository) UpdateItemRefundedQuantity(ctx context.Context, orderItemID uuid.UUID, refundedQuantity int) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE order_items SET refunded_quantity = $1 WHERE id = $2`,
		refundedQuantity, orderItemID,
	)
	return err
}

// UpdateItemRefundedQuantityTx updates the refunded quantity for an order item within a transaction
func (r *OrderRepository) UpdateItemRefundedQuantityTx(ctx context.Context, tx pgx.Tx, orderItemID uuid.UUID, refundedQuantity int) error {
	_, err := tx.Exec(ctx,
		`UPDATE order_items SET refunded_quantity = $1 WHERE id = $2`,
		refundedQuantity, orderItemID,
	)
	return err
}

// CreateRefundedOrderItem creates a record in the refunded_order_items audit table
func (r *OrderRepository) CreateRefundedOrderItem(ctx context.Context, item *models.RefundedOrderItem) error {
	_, err := r.pool.Exec(ctx,
		`INSERT INTO refunded_order_items (id, refund_id, order_id, order_item_id, quantity, price_cents, seller_id, refunded_at, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		item.ID, item.RefundID, item.OrderID, item.OrderItemID, item.Quantity,
		item.PriceCents, item.SellerID, item.RefundedAt, item.CreatedAt,
	)
	return err
}

// CreateRefundedOrderItemTx creates a record in the refunded_order_items audit table within a transaction
func (r *OrderRepository) CreateRefundedOrderItemTx(ctx context.Context, tx pgx.Tx, item *models.RefundedOrderItem) error {
	_, err := tx.Exec(ctx,
		`INSERT INTO refunded_order_items (id, refund_id, order_id, order_item_id, quantity, price_cents, seller_id, refunded_at, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		item.ID, item.RefundID, item.OrderID, item.OrderItemID, item.Quantity,
		item.PriceCents, item.SellerID, item.RefundedAt, item.CreatedAt,
	)
	return err
}

// GetRefundedItemsByOrderID gets all refunded items for an order (audit trail)
func (r *OrderRepository) GetRefundedItemsByOrderID(ctx context.Context, orderID uuid.UUID) ([]models.RefundedOrderItem, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT id, refund_id, order_id, order_item_id, quantity, price_cents, seller_id, refunded_at, created_at
		 FROM refunded_order_items WHERE order_id = $1 ORDER BY refunded_at DESC`,
		orderID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []models.RefundedOrderItem
	for rows.Next() {
		var item models.RefundedOrderItem
		err := rows.Scan(
			&item.ID, &item.RefundID, &item.OrderID, &item.OrderItemID, &item.Quantity,
			&item.PriceCents, &item.SellerID, &item.RefundedAt, &item.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	return items, rows.Err()
}

// UpdateOrderRefundStatus updates the refund status of an order
func (r *OrderRepository) UpdateOrderRefundStatus(ctx context.Context, orderID uuid.UUID, status string) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE orders SET refund_status = $1, updated_at = $2 WHERE id = $3`,
		status, time.Now(), orderID,
	)
	return err
}

// UpdateOrderRefundStatusTx updates the refund status of an order within a transaction
func (r *OrderRepository) UpdateOrderRefundStatusTx(ctx context.Context, tx pgx.Tx, orderID uuid.UUID, status string) error {
	_, err := tx.Exec(ctx,
		`UPDATE orders SET refund_status = $1, updated_at = $2 WHERE id = $3`,
		status, time.Now(), orderID,
	)
	return err
}

// GetItemsByOrderIDTx gets order items by order ID within a transaction
func (r *OrderRepository) GetItemsByOrderIDTx(ctx context.Context, tx pgx.Tx, orderID uuid.UUID) ([]models.OrderItem, error) {
	rows, err := tx.Query(ctx,
		`SELECT id, order_id, product_id, sku, seller_id, quantity, price_cents, currency, refunded_quantity, reservation_id, shipment_id, created_at
		 FROM order_items WHERE order_id = $1`,
		orderID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []models.OrderItem
	for rows.Next() {
		var item models.OrderItem
		err := rows.Scan(
			&item.ID, &item.OrderID, &item.ProductID, &item.SKU, &item.SellerID,
			&item.Quantity, &item.PriceCents, &item.Currency, &item.RefundedQuantity,
			&item.ReservationID, &item.ShipmentID, &item.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	return items, rows.Err()
}

// GetItemsByIDsTx gets order items by their IDs within a transaction
func (r *OrderRepository) GetItemsByIDsTx(ctx context.Context, tx pgx.Tx, itemIDs []uuid.UUID) ([]models.OrderItem, error) {
	if len(itemIDs) == 0 {
		return []models.OrderItem{}, nil
	}

	rows, err := tx.Query(ctx,
		`SELECT id, order_id, product_id, sku, seller_id, quantity, price_cents, currency, refunded_quantity, reservation_id, shipment_id, created_at
		 FROM order_items WHERE id = ANY($1)`,
		itemIDs,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []models.OrderItem
	for rows.Next() {
		var item models.OrderItem
		err := rows.Scan(
			&item.ID, &item.OrderID, &item.ProductID, &item.SKU, &item.SellerID,
			&item.Quantity, &item.PriceCents, &item.Currency, &item.RefundedQuantity,
			&item.ReservationID, &item.ShipmentID, &item.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	return items, rows.Err()
}

// CheckRefundExistsTx checks if a refund_id already exists in refunded_order_items within a transaction
func (r *OrderRepository) CheckRefundExistsTx(ctx context.Context, tx pgx.Tx, refundID uuid.UUID) (bool, error) {
	var count int
	err := tx.QueryRow(ctx,
		`SELECT COUNT(*) FROM refunded_order_items WHERE refund_id = $1`,
		refundID,
	).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// BeginTx begins a new database transaction
func (r *OrderRepository) BeginTx(ctx context.Context) (pgx.Tx, error) {
	return r.pool.Begin(ctx)
}

// GetItemsByIDs gets order items by their IDs
func (r *OrderRepository) GetItemsByIDs(ctx context.Context, itemIDs []uuid.UUID) ([]models.OrderItem, error) {
	if len(itemIDs) == 0 {
		return []models.OrderItem{}, nil
	}

	rows, err := r.pool.Query(ctx,
		`SELECT id, order_id, product_id, sku, seller_id, quantity, price_cents, currency, refunded_quantity, reservation_id, shipment_id, created_at
		 FROM order_items WHERE id = ANY($1)`,
		itemIDs,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var items []models.OrderItem
	for rows.Next() {
		var item models.OrderItem
		err := rows.Scan(
			&item.ID, &item.OrderID, &item.ProductID, &item.SKU, &item.SellerID,
			&item.Quantity, &item.PriceCents, &item.Currency, &item.RefundedQuantity,
			&item.ReservationID, &item.ShipmentID, &item.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}

	return items, rows.Err()
}

// CheckRefundExists checks if a refund_id already exists in refunded_order_items (for idempotency)
func (r *OrderRepository) CheckRefundExists(ctx context.Context, refundID uuid.UUID) (bool, error) {
	var count int
	err := r.pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM refunded_order_items WHERE refund_id = $1`,
		refundID,
	).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// ListOrders retrieves orders with optional buyer filtering and pagination
// buyerID filter uses STARTSWITH matching to support both email and UUID partial matches
func (r *OrderRepository) ListOrders(ctx context.Context, buyerID *string, limit, offset int) ([]models.Order, error) {
	var query string
	var args []interface{}

	if buyerID != nil {
		// Use STARTSWITH (LIKE pattern) to match both email and UUID patterns
		// This allows filtering by partial email (e.g., "buyer@") or partial UUID (e.g., "550e8400")
		query = `SELECT id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
		                created_at, updated_at, confirmed_at, cancelled_at
		         FROM orders 
		         WHERE buyer_id LIKE $1 || '%'
		         ORDER BY created_at DESC
		         LIMIT $2 OFFSET $3`
		args = []interface{}{*buyerID, limit, offset}
	} else {
		query = `SELECT id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
		                created_at, updated_at, confirmed_at, cancelled_at
		         FROM orders 
		         ORDER BY created_at DESC
		         LIMIT $1 OFFSET $2`
		args = []interface{}{limit, offset}
	}

	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var orders []models.Order
	for rows.Next() {
		var order models.Order
		var confirmedAt, cancelledAt *time.Time
		var refundStatus string

		err := rows.Scan(
			&order.ID, &order.BuyerID, &order.Status, &order.Provisional, &order.PaymentID,
			&order.TotalCents, &order.Currency, &refundStatus, &order.CreatedAt, &order.UpdatedAt,
			&confirmedAt, &cancelledAt,
		)
		if err != nil {
			return nil, err
		}

		if confirmedAt != nil {
			order.ConfirmedAt = confirmedAt
		}
		if cancelledAt != nil {
			order.CancelledAt = cancelledAt
		}
		order.RefundStatus = refundStatus

		orders = append(orders, order)
	}

	return orders, rows.Err()
}

// CountOrders counts total orders with optional buyer filtering
// buyerID filter uses STARTSWITH matching to support both email and UUID partial matches
func (r *OrderRepository) CountOrders(ctx context.Context, buyerID *string) (int, error) {
	var count int
	var err error

	if buyerID != nil {
		// Use STARTSWITH (LIKE pattern) to match both email and UUID patterns
		err = r.pool.QueryRow(ctx,
			`SELECT COUNT(*) FROM orders WHERE buyer_id LIKE $1 || '%'`,
			*buyerID,
		).Scan(&count)
	} else {
		err = r.pool.QueryRow(ctx,
			`SELECT COUNT(*) FROM orders`,
		).Scan(&count)
	}

	return count, err
}

// ListOrdersForSeller retrieves orders where the seller has items, with pagination
// Returns orders with only the items belonging to that seller
func (r *OrderRepository) ListOrdersForSeller(ctx context.Context, sellerID string, limit, offset int) ([]models.Order, error) {
	// Get distinct order IDs for orders containing items from this seller
	orderIDRows, err := r.pool.Query(ctx,
		`SELECT DISTINCT order_id 
		 FROM order_items 
		 WHERE seller_id = $1
		 ORDER BY order_id DESC
		 LIMIT $2 OFFSET $3`,
		sellerID, limit, offset,
	)
	if err != nil {
		return nil, err
	}
	defer orderIDRows.Close()

	var orderIDs []uuid.UUID
	for orderIDRows.Next() {
		var orderID uuid.UUID
		if err := orderIDRows.Scan(&orderID); err != nil {
			return nil, err
		}
		orderIDs = append(orderIDs, orderID)
	}

	if len(orderIDs) == 0 {
		return []models.Order{}, nil
	}

	// Get orders for these IDs
	query := `SELECT id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
	                 created_at, updated_at, confirmed_at, cancelled_at
	          FROM orders 
	          WHERE id = ANY($1)
	          ORDER BY created_at DESC`

	rows, err := r.pool.Query(ctx, query, orderIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var orders []models.Order
	for rows.Next() {
		var order models.Order
		var confirmedAt, cancelledAt *time.Time
		var refundStatus string

		err := rows.Scan(
			&order.ID, &order.BuyerID, &order.Status, &order.Provisional, &order.PaymentID,
			&order.TotalCents, &order.Currency, &refundStatus, &order.CreatedAt, &order.UpdatedAt,
			&confirmedAt, &cancelledAt,
		)
		if err != nil {
			return nil, err
		}

		if confirmedAt != nil {
			order.ConfirmedAt = confirmedAt
		}
		if cancelledAt != nil {
			order.CancelledAt = cancelledAt
		}
		order.RefundStatus = refundStatus

		orders = append(orders, order)
	}

	return orders, rows.Err()
}

// CountOrdersForSeller counts total orders where seller has items
func (r *OrderRepository) CountOrdersForSeller(ctx context.Context, sellerID string) (int, error) {
	var count int
	err := r.pool.QueryRow(ctx,
		`SELECT COUNT(DISTINCT order_id) FROM order_items WHERE seller_id = $1`,
		sellerID,
	).Scan(&count)
	return count, err
}
