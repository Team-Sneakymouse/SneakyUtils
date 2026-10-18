package com.sneakyutils.util

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * Parses attribute config lines without Bukkit.
 * Format: `<attribute> <amount> [operation] [slot]` — operation defaults to `add_number`.
 */
object AttributeSpecParser {

    /**
     * @param requireOperation when true, at least three tokens are required (AttributeManager legacy)
     */
    @JvmStatic
    @JvmOverloads
    fun parse(line: String, requireOperation: Boolean = false): AttributeSpec {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("attribute line is empty")
        }

        val args = trimmed.split("\\s+".toRegex())
        if (requireOperation) {
            if (args.size < 3) {
                throw IllegalArgumentException("attribute line needs name, amount, and operation")
            }
        } else if (args.size < 2) {
            throw IllegalArgumentException("attribute line needs name and amount")
        }

        val amount = try {
            args[1].toDouble()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("invalid attribute amount '${args[1]}'", e)
        }

        val operationRaw = if (args.size >= 3) args[2] else AttributeOperationNames.ADD_NUMBER
        val operation = AttributeOperationNames.canonicalize(operationRaw)
            ?: throw IllegalArgumentException("unknown attribute operation '$operationRaw'")

        val slot = if (args.size >= 4) args[3] else null
        return AttributeSpec(args[0], amount, operation, slot)
    }

    @JvmStatic
    fun stableUuid(sourceKey: String?, index: Int): UUID {
        val key = (sourceKey ?: "attributes") + ":" + index
        return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Namespaced-key path segment for modern `AttributeModifier` keys (`[a-z0-9._-]`).
     */
    @JvmStatic
    fun stableKeyPath(sourceKey: String?, index: Int): String {
        val uuid = stableUuid(sourceKey, index)
        return ("attr-$uuid").lowercase(Locale.ROOT)
    }
}
