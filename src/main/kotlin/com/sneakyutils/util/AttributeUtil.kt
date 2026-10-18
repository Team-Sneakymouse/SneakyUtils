package com.sneakyutils.util

import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier.Operation

object AttributeUtil {

    private val attributeNameMap = HashMap<String, Attribute>()
    private val operationNameMap = HashMap<String, Operation>()

    init {
        operationNameMap[AttributeOperationNames.ADD_NUMBER] = Operation.ADD_NUMBER
        operationNameMap[AttributeOperationNames.ADD_SCALAR] = Operation.ADD_SCALAR
        operationNameMap[AttributeOperationNames.MULTIPLY_SCALAR_1] = Operation.MULTIPLY_SCALAR_1
        for (
            alias in arrayOf(
                "addnumber", "add_scalar", "addscalar",
                "multiply_scalar_1", "multiplyscalar1", "multiply_scalar", "multiplyscalar",
            )
        ) {
            val canonical = AttributeOperationNames.canonicalize(alias)
            if (canonical != null) operationNameMap[alias] = operationNameMap[canonical]!!
        }

        for (attribute in Registry.ATTRIBUTE) {
            val key = attribute.key.key
            for (alias in AttributeNameAliases.candidates(key)) {
                attributeNameMap.putIfAbsent(alias, attribute)
            }
        }
    }

    @JvmStatic
    fun getAttribute(attribute: String?): Attribute? {
        if (attribute.isNullOrBlank()) return null
        for (candidate in AttributeNameAliases.candidates(attribute)) {
            val resolved = attributeNameMap[candidate]
            if (resolved != null) return resolved
        }
        return null
    }

    @JvmStatic
    fun getOperation(operation: String): Operation? {
        val canonical = AttributeOperationNames.canonicalize(operation)
        if (canonical != null) return operationNameMap[canonical]
        return operationNameMap[operation.lowercase()]
    }
}
