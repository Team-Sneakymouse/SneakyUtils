package com.sneakyutils.util

import org.bukkit.entity.Player
import java.util.Arrays

/**
 * Original credit to desht
 */
object ExperienceUtils {

    private const val MAX_LEVEL_SUPPORTED = 500

    private val xpRequiredForNextLevel = IntArray(MAX_LEVEL_SUPPORTED)
    private val xpTotalToReachLevel = IntArray(MAX_LEVEL_SUPPORTED)

    init {
        xpTotalToReachLevel[0] = 0
        for (i in 1 until 17) {
            xpRequiredForNextLevel[i - 1] = 17
            xpTotalToReachLevel[i] = i * 17
        }
        for (i in 17 until MAX_LEVEL_SUPPORTED) {
            xpRequiredForNextLevel[i - 1] = 3 * i - 31
            xpTotalToReachLevel[i] = xpTotalToReachLevel[i - 1] + xpRequiredForNextLevel[i - 1]
        }
    }

    @JvmStatic
    fun changeExp(player: Player, amt: Int) {
        var xp = getCurrentExp(player) + amt
        if (xp < 0) xp = 0

        val newLvl = getLevelFromExp(xp)
        if (newLvl >= MAX_LEVEL_SUPPORTED) return
        if (player.level != newLvl) player.level = newLvl

        val pct = (xp - xpTotalToReachLevel[newLvl]).toFloat() / xpRequiredForNextLevel[newLvl].toFloat()
        player.exp = pct
    }

    @JvmStatic
    fun getCurrentExp(player: Player): Int {
        val lvl = player.level
        return xpTotalToReachLevel[lvl] + (xpRequiredForNextLevel[lvl] * player.exp).toInt()
    }

    @JvmStatic
    fun hasExp(player: Player, amt: Int): Boolean {
        if (player.level >= MAX_LEVEL_SUPPORTED) return false
        return getCurrentExp(player) >= amt
    }

    @JvmStatic
    fun getLevelFromExp(exp: Int): Int {
        if (exp <= 0) return 0
        val pos = Arrays.binarySearch(xpTotalToReachLevel, exp)
        return if (pos < 0) -pos - 2 else pos
    }
}
