package com.sneakyutils.util

/**
 * Bukkit-free parsed attribute line: `name amount [operation] [slot]`.
 */
class AttributeSpec(
    @get:JvmName("attributeName") val attributeName: String,
    @get:JvmName("amount") val amount: Double,
    /** Canonical operation name (e.g. `add_number`). */
    @get:JvmName("operationName") val operationName: String,
    /** Raw slot token, or `null` if omitted. */
    @get:JvmName("slotName") val slotName: String?,
)
