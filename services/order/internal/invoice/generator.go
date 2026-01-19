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
	pdf.Rect(10, pdf.GetY(), 190, 8, "F")
	pdf.SetTextColor(0, 0, 0)
	pdf.SetXY(10, pdf.GetY())
	pdf.CellFormat(80, 8, "Product / SKU", "B", 0, "", false, 0, "")
	pdf.CellFormat(30, 8, "Quantity", "B", 0, "", false, 0, "")
	pdf.CellFormat(30, 8, "Unit Price", "B", 0, "", false, 0, "")
	pdf.CellFormat(30, 8, "Total", "B", 0, "", false, 0, "")
	pdf.Ln(8)

	// Items
	pdf.SetFont("Arial", "", 10)
	pdf.SetFillColor(255, 255, 255)
	pdf.SetTextColor(0, 0, 0)
	var totalCents int64

	for _, item := range items {
		itemTotal := item.PriceCents * int64(item.Quantity)
		totalCents += itemTotal

		// Truncate SKU if too long (max 35 chars for product name area)
		sku := item.SKU
		if len(sku) > 35 {
			sku = sku[:32] + "..."
		}

		// Format: "SKU: {sku}" (product name would go here if available)
		itemDescription := fmt.Sprintf("SKU: %s", sku)

		pdf.CellFormat(80, 8, itemDescription, "", 0, "", false, 0, "")
		pdf.CellFormat(30, 8, fmt.Sprintf("%d", item.Quantity), "", 0, "", false, 0, "")
		pdf.CellFormat(30, 8, formatCurrency(item.PriceCents, item.Currency), "", 0, "", false, 0, "")
		pdf.CellFormat(30, 8, formatCurrency(itemTotal, item.Currency), "", 0, "", false, 0, "")
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
