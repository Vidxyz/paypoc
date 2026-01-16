package invoice

import (
	"bytes"
	"fmt"
	"time"

	"github.com/jung-kurt/gofpdf"
	"github.com/payments-platform/order-service/internal/models"
)

type InvoiceGenerator struct{}

func NewInvoiceGenerator() *InvoiceGenerator {
	return &InvoiceGenerator{}
}

// GenerateInvoice generates a PDF invoice for an order
func (g *InvoiceGenerator) GenerateInvoice(order *models.Order, items []models.OrderItem) (*bytes.Buffer, error) {
	pdf := gofpdf.New("P", "mm", "A4", "")
	pdf.AddPage()

	// Header
	pdf.SetFont("Arial", "B", 20)
	pdf.Cell(0, 10, "INVOICE")
	pdf.Ln(10)

	// Company info (placeholder)
	pdf.SetFont("Arial", "", 12)
	pdf.Cell(0, 10, "BuyIt Platform")
	pdf.Ln(5)
	pdf.Cell(0, 10, "123 Commerce Street")
	pdf.Ln(5)
	pdf.Cell(0, 10, "Toronto, ON, Canada")
	pdf.Ln(15)

	// Order details
	pdf.SetFont("Arial", "B", 12)
	pdf.Cell(0, 10, "Order Details")
	pdf.Ln(8)

	pdf.SetFont("Arial", "", 10)
	pdf.Cell(40, 8, "Invoice Number:")
	pdf.Cell(0, 8, order.ID.String())
	pdf.Ln(6)

	pdf.Cell(40, 8, "Order Date:")
	pdf.Cell(0, 8, order.CreatedAt.Format("January 2, 2006"))
	pdf.Ln(6)

	if order.ConfirmedAt != nil {
		pdf.Cell(40, 8, "Payment Date:")
		pdf.Cell(0, 8, order.ConfirmedAt.Format("January 2, 2006"))
		pdf.Ln(6)
	}

	pdf.Cell(40, 8, "Buyer ID:")
	pdf.Cell(0, 8, order.BuyerID)
	pdf.Ln(10)

	// Items table header
	pdf.SetFont("Arial", "B", 10)
	pdf.SetFillColor(240, 240, 240)
	pdf.Cell(100, 8, "Item")
	pdf.Cell(30, 8, "Quantity")
	pdf.Cell(30, 8, "Price")
	pdf.Cell(30, 8, "Total")
	pdf.Ln(8)

	// Items
	pdf.SetFont("Arial", "", 10)
	pdf.SetFillColor(255, 255, 255)
	var totalCents int64

	for _, item := range items {
		itemTotal := item.PriceCents * int64(item.Quantity)
		totalCents += itemTotal

		pdf.Cell(100, 8, item.SKU)
		pdf.Cell(30, 8, fmt.Sprintf("%d", item.Quantity))
		pdf.Cell(30, 8, formatCurrency(item.PriceCents, item.Currency))
		pdf.Cell(30, 8, formatCurrency(itemTotal, item.Currency))
		pdf.Ln(8)
	}

	// Total
	pdf.Ln(5)
	pdf.SetFont("Arial", "B", 12)
	pdf.Cell(160, 10, "Total:")
	pdf.Cell(30, 10, formatCurrency(totalCents, order.Currency))
	pdf.Ln(15)

	// Footer
	pdf.SetFont("Arial", "I", 8)
	pdf.Cell(0, 10, "Thank you for your purchase!")
	pdf.Ln(5)
	pdf.Cell(0, 10, fmt.Sprintf("Generated on %s", time.Now().Format("January 2, 2006 at 3:04 PM")))

	// Output to buffer
	var buf bytes.Buffer
	err := pdf.Output(&buf)
	if err != nil {
		return nil, fmt.Errorf("failed to generate PDF: %w", err)
	}

	return &buf, nil
}

func formatCurrency(cents int64, currency string) string {
	amount := float64(cents) / 100.0
	return fmt.Sprintf("%s %.2f", currency, amount)
}
