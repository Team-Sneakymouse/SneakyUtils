package com.sneakyutils.util

import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * Entity Persistent Data Container helpers, plus ephemeral object attachment
 * keyed by entity UUID (PDC cannot store arbitrary Java objects).
 *
 * Default keys use the `sneakyutils` namespace. Pass an explicit namespace
 * (e.g. `magicspells`) when reading/writing another plugin's existing PDC.
 */
object EntityPdc {

    const val DEFAULT_NAMESPACE = "sneakyutils"

    /**
     * Builds a PDC key under [DEFAULT_NAMESPACE]. Names are lowercased and
     * non-`[a-z0-9_-./]` chars become `_`.
     */
    @JvmStatic
    fun key(name: String): NamespacedKey = key(DEFAULT_NAMESPACE, name)

    @JvmStatic
    fun key(namespace: String, name: String): NamespacedKey {
        val sanitized = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.\\-/]"), "_")
        return NamespacedKey(namespace, sanitized)
    }

    @JvmStatic
    fun hasFlag(entity: Entity, key: NamespacedKey): Boolean =
        entity.persistentDataContainer.has(key, PersistentDataType.BYTE)

    @JvmStatic
    fun setFlag(entity: Entity, key: NamespacedKey) {
        entity.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
    }

    @JvmStatic
    fun remove(entity: Entity, key: NamespacedKey) {
        entity.persistentDataContainer.remove(key)
    }

    @JvmStatic
    fun setString(entity: Entity, key: NamespacedKey, value: String) {
        entity.persistentDataContainer.set(key, PersistentDataType.STRING, value)
    }

    @JvmStatic
    fun getString(entity: Entity, key: NamespacedKey): String? =
        entity.persistentDataContainer.get(key, PersistentDataType.STRING)

    @JvmStatic
    fun setDouble(entity: Entity, key: NamespacedKey, value: Double) {
        entity.persistentDataContainer.set(key, PersistentDataType.DOUBLE, value)
    }

    @JvmStatic
    fun getDouble(entity: Entity, key: NamespacedKey): Double? =
        entity.persistentDataContainer.get(key, PersistentDataType.DOUBLE)

    @JvmStatic
    fun setFloat(entity: Entity, key: NamespacedKey, value: Float) {
        entity.persistentDataContainer.set(key, PersistentDataType.FLOAT, value)
    }

    @JvmStatic
    fun getFloat(entity: Entity, key: NamespacedKey): Float? =
        entity.persistentDataContainer.get(key, PersistentDataType.FLOAT)

    @JvmStatic
    fun setByte(entity: Entity, key: NamespacedKey, value: Byte) {
        entity.persistentDataContainer.set(key, PersistentDataType.BYTE, value)
    }

    @JvmStatic
    fun getByte(entity: Entity, key: NamespacedKey): Byte? =
        entity.persistentDataContainer.get(key, PersistentDataType.BYTE)

    /**
     * In-memory store for values that cannot live in PDC (object graphs, etc.).
     * Presence is mirrored with a PDC flag so [has] works after restarts
     * only for the flag — attached objects are always session-local.
     */
    class ObjectStore<T> {

        private val flagKey: NamespacedKey
        private val values: MutableMap<UUID, T> = ConcurrentHashMap()

        constructor(flagKeyName: String) : this(DEFAULT_NAMESPACE, flagKeyName)

        constructor(namespace: String, flagKeyName: String) {
            this.flagKey = key(namespace, flagKeyName)
        }

        fun has(entity: Entity): Boolean = hasFlag(entity, flagKey)

        fun get(entity: Entity): T? = values[entity.uniqueId]

        fun set(entity: Entity, value: T) {
            values[entity.uniqueId] = value
            setFlag(entity, flagKey)
        }

        fun computeIfAbsent(entity: Entity, mappingFunction: Function<UUID, T>): T {
            val value = values.computeIfAbsent(entity.uniqueId, mappingFunction)
            setFlag(entity, flagKey)
            return value
        }

        fun remove(entity: Entity) {
            values.remove(entity.uniqueId)
            EntityPdc.remove(entity, flagKey)
        }

        fun clear() {
            values.clear()
        }
    }
}
