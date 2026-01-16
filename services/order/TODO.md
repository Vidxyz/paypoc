# Order Service TODOs

## Required Inventory Service Endpoint

The Order Service needs an internal endpoint in the Inventory Service to convert soft reservations to hard allocations by reservation ID.

**Required Endpoint:**
```
POST /internal/reservations/{reservationId}/allocate
Authorization: Bearer {internal_token}
```

This endpoint should:
1. Look up the reservation by ID
2. Get the inventoryId from the reservation
3. Convert the soft reservation to a hard allocation
4. Return the new hard allocation reservation

**Alternative:** If this endpoint is not available, the Order Service would need to:
1. First call `GET /internal/reservations/{reservationId}` to get the reservation details (including inventoryId)
2. Then call `POST /internal/reservations/allocate` with the inventoryId

Currently, the code attempts Option 1 (direct conversion by reservationId). If that fails, it will need to be updated to use Option 2.


## Testing

- [ ] Test checkout flow end-to-end
- [ ] Test order confirmation via Kafka event
- [ ] Test invoice generation
- [ ] Test error handling (inventory allocation failures, payment failures)
- [ ] Test multi-seller orders

## Future Enhancements

- [ ] Add order status updates based on shipment status
- [ ] Add order cancellation endpoint
- [ ] Add order history endpoint
- [ ] Add seller-specific invoice generation
- [ ] Add email notification when invoice is generated
- [ ] Add invoice caching/storage option
