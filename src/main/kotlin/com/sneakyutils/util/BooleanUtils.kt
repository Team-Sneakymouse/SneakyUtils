package com.sneakyutils.util

object BooleanUtils {

    private val yesStrings = setOf("yes", "true")
    private val noStrings = setOf("no", "false")

    @JvmStatic
    fun isYes(toCheck: String): Boolean =
        yesStrings.contains(toCheck.trim().lowercase())

    @JvmStatic
    fun isNo(toCheck: String): Boolean =
        noStrings.contains(toCheck.trim().lowercase())
}
