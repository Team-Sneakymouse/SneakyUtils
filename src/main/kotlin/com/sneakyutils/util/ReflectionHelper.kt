package com.sneakyutils.util

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections

class ReflectionHelper<E>(type: Class<out E>, vararg fieldNames: String) {

    // Field references
    private val fields = HashMap<String, Field>()

    // Reference to the target class
    val targetClass: Class<out E> = type

    // Due to the complexity of getting methods
    // This will map usage assigned keys rather than the method name as the key
    private val methods = HashMap<String, Method>()

    init {
        for (fieldName in fieldNames) {
            try {
                val field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                fields[fieldName] = field
            } catch (e: NoSuchFieldException) {
                e.printStackTrace()
            }
        }
    }

    fun getMethods(): Map<String, Method> = Collections.unmodifiableMap(methods)

    fun getMethod(key: String): Method? = methods[key]

    // Returns the old method assigned to this key
    fun cacheMethod(key: String, method: Method): Method? {
        makeAccessible(method)
        return methods.put(key, method)
    }

    fun getInt(`object`: E, field: String): Int {
        return try {
            fields[field]!!.getInt(`object`)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
            0
        }
    }

    fun setInt(`object`: E, field: String, value: Int) {
        try {
            fields[field]!!.setInt(`object`, value)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    fun getByte(`object`: E, field: String): Byte {
        return try {
            fields[field]!!.getByte(`object`)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
            0
        }
    }

    fun setByte(`object`: E, field: String, value: Byte) {
        try {
            fields[field]!!.setByte(`object`, value)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    fun getString(`object`: E, field: String): String? {
        return try {
            fields[field]!![`object`] as String?
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
            null
        }
    }

    fun setString(`object`: E, field: String, value: String?) {
        try {
            fields[field]!![`object`] = value
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    fun get(`object`: E, field: String): Any? {
        return try {
            fields[field]!![`object`]
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
            null
        }
    }

    fun set(`object`: E, field: String, value: Any?) {
        try {
            fields[field]!![`object`] = value
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    fun invoke(`object`: E, methodKey: String, vararg args: Any?): Any? {
        return try {
            val method = methods[methodKey] ?: return null
            method.invoke(`object`, *args)
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
    }

    companion object {
        @JvmStatic
        fun makeAccessible(accessibleObject: AccessibleObject) {
            try {
                accessibleObject.isAccessible = true
            } catch (exception: Exception) {
                throw RuntimeException(exception)
            }
        }
    }
}
