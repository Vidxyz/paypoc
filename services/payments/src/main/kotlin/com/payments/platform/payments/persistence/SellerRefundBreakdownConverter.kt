package com.payments.platform.payments.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.payments.domain.SellerRefundBreakdown
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA converter for SellerRefundBreakdown list to/from JSONB.
 */
@Converter
class SellerRefundBreakdownConverter : AttributeConverter<List<SellerRefundBreakdown>?, String> {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    
    override fun convertToDatabaseColumn(attribute: List<SellerRefundBreakdown>?): String {
        if (attribute == null || attribute.isEmpty()) {
            return "[]"
        }
        return objectMapper.writeValueAsString(attribute)
    }
    
    override fun convertToEntityAttribute(dbData: String?): List<SellerRefundBreakdown>? {
        if (dbData == null || dbData.isBlank() || dbData == "[]") {
            return null
        }
        return try {
            objectMapper.readValue<List<SellerRefundBreakdown>>(dbData)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse sellerRefundBreakdown JSON: $dbData", e)
        }
    }
}
