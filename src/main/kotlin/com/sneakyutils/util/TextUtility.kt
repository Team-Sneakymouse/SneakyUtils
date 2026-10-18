package com.sneakyutils.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * MiniMessage helpers and light text wrapping for plugin messages.
 */
object TextUtility {

    fun convertToComponent(message: String): Component {
        return MiniMessage.miniMessage()
            .deserialize(replaceFormatCodes(message))
            .decoration(TextDecoration.ITALIC, false)
    }

    fun replaceFormatCodes(message: String): String {
        return message.replace("\u00BA", "&")
            .replace("\u00A7", "&")
            .replace("&1", "<dark_blue>")
            .replace("&2", "<dark_green>")
            .replace("&3", "<dark_aqua>")
            .replace("&4", "<dark_red>")
            .replace("&5", "<dark_purple>")
            .replace("&6", "<gold>")
            .replace("&7", "<gray>")
            .replace("&8", "<dark_gray>")
            .replace("&9", "<blue>")
            .replace("&0", "<black>")
            .replace("&a", "<green>")
            .replace("&b", "<aqua>")
            .replace("&c", "<red>")
            .replace("&d", "<light_purple>")
            .replace("&e", "<yellow>")
            .replace("&f", "<white>")
            .replace("&k", "<obf>")
            .replace("&l", "<b>")
            .replace("&m", "<st>")
            .replace("&n", "<u>")
            .replace("&o", "<i>")
            .replace("&r", "<reset>")
            .replace("&#([A-Fa-f0-9]{6})".toRegex(), "<color:#$1>")
    }

    /**
     * Word-wraps [text] to at most [maxLineLength] characters per line (spaces included).
     *
     * Uses standard greedy wrapping, then tightens the effective width so the same number of
     * lines share length more evenly (avoids a long final line with a short remainder dumped on it).
     * A single word longer than [maxLineLength] is kept on its own line unsplit.
     */
    fun splitIntoLines(text: String, maxLineLength: Int): List<String> {
        require(maxLineLength > 0) { "maxLineLength must be positive" }
        val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val greedyLines = wrapGreedy(words, maxLineLength)
        if (greedyLines.size <= 1) return greedyLines

        var low = 1
        var high = maxLineLength
        var bestWidth = maxLineLength
        while (low <= high) {
            val mid = (low + high) / 2
            if (wrapGreedy(words, mid).size <= greedyLines.size) {
                bestWidth = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return wrapGreedy(words, bestWidth)
    }

    private fun wrapGreedy(words: List<String>, maxLineLength: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            if (current.isEmpty()) {
                current.append(word)
                continue
            }
            if (current.length + 1 + word.length <= maxLineLength) {
                current.append(' ').append(word)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) {
            lines.add(current.toString())
        }
        return lines
    }
}
