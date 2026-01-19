package com.payments.platform.payments.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.payments.domain.OrderItemRefundSnapshot
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA converter for OrderItemRefundSnapshot list to/from JSONB.
 */
@Converter
class OrderItemRefundSnapshotConverter : AttributeConverter<List<OrderItemRefundSnapshot>?, String> {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    
    override fun convertToDatabaseColumn(attribute: List<OrderItemRefundSnapshot>?): String {
        if (attribute == null || attribute.isEmpty()) {
            return "[]"
        }
        return objectMapper.writeValueAsString(attribute)
    }
    
    override fun convertToEntityAttribute(dbData: String?): List<OrderItemRefundSnapshot>? {
        if (dbData == null || dbData.isBlank() || dbData == "[]") {
            return null
        }
        return try {
            objectMapper.readValue<List<OrderItemRefundSnapshot>>(dbData)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse orderItemsRefunded JSON: $dbData", e)
        }
    }
}
