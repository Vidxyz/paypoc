package repository

import (
	"context"
	"fmt"
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
		`INSERT INTO orders (id, buyer_id, status, provisional, payment_id, total_cents, currency, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		order.ID, order.BuyerID, order.Status, order.Provisional, order.PaymentID,
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

	err := r.pool.QueryRow(ctx,
		`SELECT id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
		        created_at, updated_at, confirmed_at, cancelled_at
		 FROM orders WHERE id = $1`,
		id,
	).Scan(
		&order.ID, &order.BuyerID, &order.Status, &order.Provisional, &order.PaymentID,
		&order.TotalCents, &order.Currency, &refundStatus, &order.CreatedAt, &order.UpdatedAt,
		&confirmedAt, &cancelledAt,
	)
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
	rows, err := r.pool.Query(ctx,
		`SELECT id, order_id, seller_id, status, provisional, tracking_number, carrier, 
		        shipped_at, delivered_at, created_at, updated_at
		 FROM shipments WHERE order_id = $1`,
		orderID,
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

func (r *OrderRepository) GetByPaymentID(ctx context.Context, paymentID uuid.UUID) (*models.Order, error) {
	var order models.Order
	var confirmedAt, cancelledAt *time.Time
	var refundStatus string

	err := r.pool.QueryRow(ctx,
		`SELECT id, buyer_id, status, provisional, payment_id, total_cents, currency, refund_status,
		        created_at, updated_at, confirmed_at, cancelled_at
		 FROM orders WHERE payment_id = $1`,
		paymentID,
	).Scan(
		&order.ID, &order.BuyerID, &order.Status, &order.Provisional, &order.PaymentID,
		&order.TotalCents, &order.Currency, &refundStatus, &order.CreatedAt, &order.UpdatedAt,
		&confirmedAt, &cancelledAt,
	)
	if err != nil {
		return nil, err
	}

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

func (r *OrderRepository) CancelOrder(ctx context.Context, orderID uuid.UUID, cancelledAt time.Time) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE orders SET status = $1, cancelled_at = $2, updated_at = $3 WHERE id = $4`,
		models.OrderStatusCancelled, cancelledAt, time.Now(), orderID,
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
