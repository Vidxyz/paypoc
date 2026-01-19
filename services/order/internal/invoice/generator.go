package invoice

import (
	"bytes"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jung-kurt/gofpdf"
	"github.com/payments-platform/order-service/internal/models"
)

type InvoiceGenerator struct{}

func NewInvoiceGenerator() *InvoiceGenerator {
	return &InvoiceGenerator{}
}

// GenerateInvoice generates a PDF invoice for an order
func (g *InvoiceGenerator) GenerateInvoice(order *models.Order, items []models.OrderItem, productNames map[uuid.UUID]string) (*bytes.Buffer, error) {
	pdf := gofpdf.New("P", "mm", "A4", "")

	// Calculate totals and refunds
	var originalTotalCents int64
	var totalRefundedCents int64
	hasRefunds := order.RefundStatus != "NONE"

	for _, item := range items {
		originalTotalCents += item.PriceCents * int64(item.Quantity)
		if item.RefundedQuantity > 0 {
			totalRefundedCents += item.PriceCents * int64(item.RefundedQuantity)
		}
	}
	adjustedTotalCents := originalTotalCents - totalRefundedCents

	// Start first page
	pdf.AddPage()

	// Header - compact
	headerHeight := 25.0
	pdf.SetFillColor(50, 50, 150) // Dark blue
	pdf.Rect(0, 0, 210, headerHeight, "F")
	pdf.SetTextColor(255, 255, 255) // White text
	pdf.SetFont("Arial", "B", 18)
	pdf.SetXY(10, 9)
	pdf.Cell(0, 6, "INVOICE")

	// Refund badge if applicable
	if hasRefunds {
		pdf.SetFillColor(200, 50, 50) // Red for refunds
		pdf.SetXY(135, 7)
		pdf.Rect(135, 7, 65, 5, "F")
		pdf.SetFont("Arial", "B", 7)
		pdf.SetTextColor(255, 255, 255)
		refundLabel := "REFUNDED"
		if order.RefundStatus == "PARTIAL" {
			refundLabel = "PARTIAL REFUND"
		}
		pdf.SetXY(135, 8.5)
		pdf.CellFormat(65, 5, refundLabel, "", 0, "C", false, 0, "")
	}

	// Start content below header
	pdf.SetY(headerHeight + 2)

	// Company info - compact
	pdf.SetTextColor(0, 0, 0) // Black text
	pdf.SetFont("Arial", "B", 10)
	pdf.Cell(0, 4, "BuyIt Platform")
	pdf.Ln(4.5)
	pdf.SetFont("Arial", "", 7)
	pdf.Cell(0, 3, "123 Commerce Street, Toronto, ON, Canada")
	pdf.Ln(5)

	// Order details - compact single line layout
	pdf.SetFillColor(245, 245, 245) // Light gray
	orderBoxHeight := 18.0
	pdf.Rect(10, pdf.GetY(), 190, orderBoxHeight, "F")
	pdf.SetXY(15, pdf.GetY()+2.5)

	pdf.SetFont("Arial", "B", 8)
	pdf.Cell(0, 4, "Order Information")
	pdf.Ln(3)

	pdf.SetFont("Arial", "", 7)
	pdf.Cell(38, 3.5, "Invoice:")
	pdf.SetFont("Arial", "B", 7)
	pdf.Cell(52, 3.5, order.ID.String())

	pdf.SetFont("Arial", "", 7)
	pdf.SetXY(115, pdf.GetY())
	pdf.Cell(25, 3.5, "Order Date:")
	pdf.SetFont("Arial", "", 7)
	pdf.Cell(0, 3.5, order.CreatedAt.Format("Jan 2, 2006"))
	pdf.Ln(3)

	pdf.SetXY(15, pdf.GetY())
	pdf.SetFont("Arial", "", 7)
	pdf.Cell(38, 3.5, "Payment Date:")
	pdf.SetFont("Arial", "", 7)
	if order.ConfirmedAt != nil {
		pdf.Cell(52, 3.5, order.ConfirmedAt.Format("Jan 2, 2006"))
	} else {
		pdf.Cell(52, 3.5, "—")
	}

	pdf.SetXY(115, pdf.GetY())
	pdf.SetFont("Arial", "", 7)
	pdf.Cell(25, 3.5, "Buyer ID:")
	pdf.SetFont("Arial", "", 7)
	pdf.Cell(0, 3.5, order.BuyerID)
	pdf.Ln(5)

	// Items table header - compact
	const (
		tableX     = 10.0
		productW   = 100.0
		qtyW       = 20.0
		unitPriceW = 25.0
		totalW     = 35.0
	)
	pdf.SetFillColor(70, 70, 70)    // Dark gray header
	pdf.SetTextColor(255, 255, 255) // White text
	pdf.SetFont("Arial", "B", 7)
	pdf.SetXY(tableX, pdf.GetY())
	pdf.CellFormat(productW, 6, "Product", "1", 0, "L", true, 0, "")
	pdf.CellFormat(qtyW, 6, "Qty", "1", 0, "C", true, 0, "")
	pdf.CellFormat(unitPriceW, 6, "Unit Price", "1", 0, "R", true, 0, "")
	pdf.CellFormat(totalW, 6, "Total", "1", 0, "R", true, 0, "")
	pdf.Ln(6)

	// Items - compact rows with product name and SKU
	pdf.SetTextColor(0, 0, 0) // Black text
	rowHeight := 13.0
	lineHeight := 4.0
	alternateRow := false

	for _, item := range items {
		// Check if we need a new page (leave room for summary and footer)
		if pdf.GetY()+rowHeight > 230 {
			pdf.AddPage()
			// Redraw header on new page
			pdf.SetFillColor(70, 70, 70)
			pdf.SetTextColor(255, 255, 255)
			pdf.SetFont("Arial", "B", 7)
			pdf.SetXY(tableX, pdf.GetY())
			pdf.CellFormat(productW, 6, "Product", "1", 0, "L", true, 0, "")
			pdf.CellFormat(qtyW, 6, "Qty", "1", 0, "C", true, 0, "")
			pdf.CellFormat(unitPriceW, 6, "Unit Price", "1", 0, "R", true, 0, "")
			pdf.CellFormat(totalW, 6, "Total", "1", 0, "R", true, 0, "")
			pdf.Ln(6)
			pdf.SetTextColor(0, 0, 0)
			alternateRow = false
		}

		startY := pdf.GetY()
		if alternateRow {
			pdf.SetFillColor(250, 250, 250)
			pdf.Rect(tableX, startY, productW+qtyW+unitPriceW+totalW, rowHeight, "F")
		}
		alternateRow = !alternateRow

		// Draw cell borders
		pdf.Rect(tableX, startY, productW, rowHeight, "D")
		pdf.Rect(tableX+productW, startY, qtyW, rowHeight, "D")
		pdf.Rect(tableX+productW+qtyW, startY, unitPriceW, rowHeight, "D")
		pdf.Rect(tableX+productW+qtyW+unitPriceW, startY, totalW, rowHeight, "D")

		// Alternate row colors
		originalItemTotal := item.PriceCents * int64(item.Quantity)
		refundedItemTotal := item.PriceCents * int64(item.RefundedQuantity)
		remainingItemTotal := originalItemTotal - refundedItemTotal

		// Product name and SKU - 2 lines
		productName, hasName := productNames[item.ProductID]
		sku := truncateString(item.SKU, 35)

		// Product column - 2 lines: name (bold) and SKU (smaller, gray)
		pdf.SetXY(tableX+1, startY+1)
		if hasName && productName != "" {
			// Product name (bold, larger)
			truncatedName := truncateString(productName, 42)
			pdf.SetFont("Arial", "B", 7)
			pdf.CellFormat(productW-2, lineHeight, truncatedName, "", 0, "L", false, 0, "")
			// SKU (smaller, gray, below name)
			pdf.SetXY(tableX+1, startY+1+lineHeight)
			pdf.SetFont("Arial", "", 6)
			pdf.SetTextColor(100, 100, 100) // Gray
			pdf.CellFormat(productW-2, lineHeight, "SKU: "+sku, "", 0, "L", false, 0, "")
			pdf.SetTextColor(0, 0, 0) // Reset to black
		} else {
			// Fallback: SKU only
			pdf.SetFont("Arial", "", 7)
			pdf.CellFormat(productW-2, lineHeight, "SKU: "+sku, "", 0, "L", false, 0, "")
		}

		// Quantity column
		qtyX := tableX + productW
		pdf.SetXY(qtyX, startY+1)
		pdf.SetFont("Arial", "", 7)
		quantityText := fmt.Sprintf("%d", item.Quantity)
		if item.RefundedQuantity > 0 {
			quantityText = fmt.Sprintf("%d", item.Quantity)
			pdf.CellFormat(qtyW, lineHeight, quantityText, "", 0, "C", false, 0, "")
			pdf.SetXY(qtyX, startY+1+lineHeight)
			pdf.SetFont("Arial", "", 6)
			pdf.SetTextColor(120, 120, 120)
			pdf.CellFormat(qtyW, lineHeight, fmt.Sprintf("(%d refunded)", item.RefundedQuantity), "", 0, "C", false, 0, "")
			pdf.SetTextColor(0, 0, 0)
		} else {
			pdf.CellFormat(qtyW, lineHeight, quantityText, "", 0, "C", false, 0, "")
		}

		// Unit Price (centered vertically)
		unitX := tableX + productW + qtyW
		pdf.SetXY(unitX, startY+(rowHeight-lineHeight)/2)
		pdf.SetFont("Arial", "", 7)
		pdf.CellFormat(unitPriceW, lineHeight, formatCurrency(item.PriceCents, item.Currency), "", 0, "R", false, 0, "")

		// Total column
		totalX := tableX + productW + qtyW + unitPriceW
		if item.RefundedQuantity > 0 {
			// Show refund breakdown: original (gray), refunded (red), final (green)
		pdf.SetXY(totalX, startY+1)
			pdf.SetTextColor(120, 120, 120)
			pdf.SetFont("Arial", "", 6)
			pdf.CellFormat(totalW, lineHeight, formatCurrency(originalItemTotal, item.Currency), "", 0, "R", false, 0, "")

		pdf.SetXY(totalX, startY+1+lineHeight)
			pdf.SetTextColor(200, 50, 50)
			pdf.CellFormat(totalW, lineHeight, "-"+formatCurrency(refundedItemTotal, item.Currency), "", 0, "R", false, 0, "")

		pdf.SetXY(totalX, startY+1+lineHeight*2)
			pdf.SetTextColor(50, 150, 50)
			pdf.SetFont("Arial", "B", 7)
			pdf.CellFormat(totalW, lineHeight, formatCurrency(remainingItemTotal, item.Currency), "", 0, "R", false, 0, "")
			pdf.SetTextColor(0, 0, 0)
		} else {
			// No refund - just show total
			pdf.SetXY(totalX, startY+(rowHeight-lineHeight)/2)
			pdf.SetFont("Arial", "", 7)
			pdf.CellFormat(totalW, lineHeight, formatCurrency(originalItemTotal, item.Currency), "", 0, "R", false, 0, "")
		}

		// Move to next row
		pdf.SetY(startY + rowHeight)
	}

	// Summary section - compact
	pdf.Ln(4)
	summaryHeight := 16.0
	if hasRefunds {
		summaryHeight = 24.0
	}
	// Ensure summary and footer fit on current page
	if pdf.GetY()+summaryHeight+20 > 287 {
		pdf.AddPage()
	}
	pdf.SetFillColor(245, 245, 245)
	pdf.Rect(115, pdf.GetY(), 85, summaryHeight, "F")
	pdf.SetXY(120, pdf.GetY()+3)

	// Original Total
	pdf.SetFont("Arial", "", 7)
	pdf.Cell(48, 4, "Original Total:")
	pdf.SetFont("Arial", "B", 7)
	pdf.Cell(0, 4, formatCurrency(originalTotalCents, order.Currency))
	pdf.Ln(3.5)

	// Refunded amount (if applicable)
	if hasRefunds {
		pdf.SetXY(120, pdf.GetY())
		pdf.SetFont("Arial", "", 7)
		pdf.SetTextColor(200, 50, 50)
		pdf.Cell(48, 4, "Refunded:")
		pdf.SetFont("Arial", "B", 7)
		pdf.Cell(0, 4, "-"+formatCurrency(totalRefundedCents, order.Currency))
		pdf.Ln(2.5)
		pdf.SetTextColor(0, 0, 0)

		// Divider line
		pdf.SetXY(120, pdf.GetY()+2)
		pdf.Line(120, pdf.GetY(), 200, pdf.GetY())
		pdf.Ln(4)

		// Final Total
		pdf.SetXY(120, pdf.GetY())
		pdf.SetFont("Arial", "B", 9)
		pdf.SetTextColor(50, 150, 50)
		pdf.Cell(48, 5, "Final Total:")
		pdf.Cell(0, 5, formatCurrency(adjustedTotalCents, order.Currency))
		pdf.SetTextColor(0, 0, 0)
	} else {
		// No refunds - just show total
		pdf.SetXY(120, pdf.GetY())
		pdf.SetFont("Arial", "B", 9)
		pdf.Cell(48, 5, "Total:")
		pdf.Cell(0, 5, formatCurrency(originalTotalCents, order.Currency))
	}

	// Footer - compact, keep on same page when possible
	if pdf.GetY() < 272 {
		pdf.SetY(272)
	} else {
		pdf.Ln(2)
	}

	pdf.SetFillColor(240, 240, 240)
	pdf.Rect(0, pdf.GetY(), 210, 13, "F")
	pdf.SetXY(10, pdf.GetY()+1.5)
	pdf.SetFont("Arial", "I", 5)
	pdf.SetTextColor(100, 100, 100)
	pdf.Cell(0, 2.5, "Thank you for your purchase!")
	pdf.Ln(2)
	pdf.Cell(0, 2.5, fmt.Sprintf("Generated on %s", time.Now().Format("Jan 2, 2006 3:04 PM")))
	pdf.Ln(1.5)
	pdf.Cell(0, 2.5, "This is an automated invoice. For questions, contact support.")

	// Output to buffer
	var buf bytes.Buffer
	err := pdf.Output(&buf)
	if err != nil {
		return nil, fmt.Errorf("failed to generate PDF: %w", err)
	}

	return &buf, nil
}

// truncateString truncates a string to maxLen characters, adding "..." if truncated
func truncateString(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	if maxLen <= 3 {
		return s[:maxLen]
	}
	return s[:maxLen-3] + "..."
}

func formatCurrency(cents int64, currency string) string {
	amount := float64(cents) / 100.0
	return fmt.Sprintf("%s %.2f", currency, amount)
}
