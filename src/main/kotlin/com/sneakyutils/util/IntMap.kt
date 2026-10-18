package com.sneakyutils.util

class IntMap<T> {

    private var map: MutableMap<T, Int> = HashMap()

    fun put(key: T, value: Int): Int {
        val prev = map.put(key, value)
        return prev ?: 0
    }

    fun set(key: T, value: Int) {
        put(key, value)
    }

    fun get(key: T): Int = map[key] ?: 0

    fun remove(key: T): Int {
        val value = map.remove(key)
        return value ?: 0
    }

    fun size(): Int = map.size

    fun contains(key: T): Boolean = map.containsKey(key)

    fun containsKey(key: T): Boolean = map.containsKey(key)

    fun containsValue(value: Int): Boolean = map.containsValue(value)

    @JvmOverloads
    fun increment(key: T, amount: Int = 1): Int {
        val value = get(key) + amount
        put(key, value)
        return value
    }

    @JvmOverloads
    fun decrement(key: T, amount: Int = 1): Int {
        val value = get(key) - amount
        put(key, value)
        return value
    }

    fun multiply(key: T, amount: Int): Int {
        val value = get(key) * amount
        put(key, value)
        return value
    }

    fun keySet(): Set<T> = map.keys

    fun clear() {
        map.clear()
    }

    fun isEmpty(): Boolean = map.isEmpty()

    fun putAll(otherMap: IntMap<out T>) {
        map.putAll(otherMap.map)
    }

    fun putAll(otherMap: Map<out T, Int>) {
        map.putAll(otherMap)
    }

    fun getIntegerMap(): MutableMap<T, Int> = map

    fun clone(): IntMap<T> {
        val newMap = IntMap<T>()
        newMap.putAll(map)
        return newMap
    }

    fun useTreeMap() {
        val newMap: MutableMap<T, Int> = java.util.TreeMap(map)
        map.clear()
        map = newMap
    }
}
