package com.payments.platform.payments.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.payments.platform.payments.domain.SellerBreakdown
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA converter for SellerBreakdown list to/from JSONB.
 */
@Converter
class SellerBreakdownConverter : AttributeConverter<List<SellerBreakdown>, String> {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    
    override fun convertToDatabaseColumn(attribute: List<SellerBreakdown>?): String {
        if (attribute == null || attribute.isEmpty()) {
            return "[]"
        }
        return objectMapper.writeValueAsString(attribute)
    }
    
    override fun convertToEntityAttribute(dbData: String?): List<SellerBreakdown> {
        if (dbData == null || dbData.isBlank() || dbData == "[]") {
            return emptyList()
        }
        return try {
            objectMapper.readValue<List<SellerBreakdown>>(dbData)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse sellerBreakdown JSON: $dbData", e)
        }
    }
}
