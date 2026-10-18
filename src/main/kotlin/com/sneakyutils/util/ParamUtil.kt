package com.sneakyutils.util

/**
 * Whitespace splitting with optional quote-delimited arguments.
 * Kept free of Bukkit so it can be unit-tested in isolation.
 */
object ParamUtil {

    /**
     * Splits a parameter string on whitespace, treating `"` or `'` as text
     * qualifiers: spaces inside a matched quote pair belong to that argument, and the
     * quote characters themselves are not included in the result.
     *
     * @param string input to split
     * @param max maximum number of arguments; if `> 0`, the last argument
     * consumes the remainder of the input (quotes in that remainder are
     * not specially parsed)
     */
    @JvmStatic
    @JvmOverloads
    fun splitParams(string: String?, max: Int = 0): Array<String> {
        if (string == null) return arrayOf("")

        val trimmed = string.trim()
        // Match String.split on empty input: a single empty element (keeps cast-command checks safe).
        if (trimmed.isEmpty()) return arrayOf("")

        val list = ArrayList<String>()
        val building = StringBuilder()
        var quote = '\u0000'
        var inArg = false

        val length = trimmed.length
        var i = 0
        while (i < length) {
            val c = trimmed[i]

            if (max > 0 && list.size == max - 1) {
                if (!inArg) {
                    // Skip leading whitespace before the final remainder arg.
                    if (c == ' ' || c == '\t') {
                        i++
                        continue
                    }
                    inArg = true
                }
                building.append(trimmed.substring(i))
                break
            }

            if (quote != '\u0000') {
                if (c == quote) {
                    quote = '\u0000'
                    list.add(building.toString())
                    building.setLength(0)
                    inArg = false
                } else {
                    building.append(c)
                }
                i++
                continue
            }

            if (c == ' ' || c == '\t') {
                if (inArg) {
                    list.add(building.toString())
                    building.setLength(0)
                    inArg = false
                }
                i++
                continue
            }

            if (!inArg && (c == '"' || c == '\'')) {
                quote = c
                inArg = true
                i++
                continue
            }

            building.append(c)
            inArg = true
            i++
        }

        if (inArg || quote != '\u0000') list.add(building.toString())

        return list.toTypedArray()
    }

    @JvmStatic
    @JvmOverloads
    fun splitParams(split: Array<String>?, max: Int = 0): Array<String> =
        splitParams(join(split), max)

    private fun join(array: Array<String>?): String {
        if (array == null || array.isEmpty()) return ""
        val sb = StringBuilder(array[0])
        for (i in 1 until array.size) {
            sb.append(' ')
            sb.append(array[i])
        }
        return sb.toString()
    }
}
