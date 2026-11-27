package com.sneakyutils.util

import com.sneakyutils.SneakyUtils
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID

/**
 * A flipbook animation on one player's screen, as MagicSpells' `fontanimation` effect drew it: the glyphs of a
 * resource-pack font shown one after another as the subtitle, title or action bar.
 *
 * Frames are numbered the MagicSpells way: frame n is the glyph whose last three hex digits are n's decimal digits
 * (frame 9 is `\uE009`, frame 10 is `\uE010`), and frames wrap from 999 back to 0. Each frame stays up for a second so
 * a late tick never flickers, and the screen is cleared when the animation ends.
 *
 * One animation per player and [Part] at a time: starting another replaces the one running there. Tasks belong to
 * SneakyUtils and stop when the player leaves. Server thread only.
 */
object FontAnimations {

    /** Where on the screen the frames show. */
    enum class Part { SUBTITLE, TITLE, ACTION_BAR }

    private val TIMES = Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1))
    private val running = HashMap<Pair<UUID, Part>, BukkitTask>()

    /**
     * Plays frames of [font] (a key such as `lom:bellanimation`) to [player]: starting at [startFrame], one every
     * [interval] ticks for [durationTicks] ticks (so `durationTicks / interval` frames, at least one), after [delay]
     * ticks. Returns false and plays nothing when [font] isn't a valid key.
     */
    @JvmStatic
    @JvmOverloads
    fun play(
        player: Player,
        font: String,
        durationTicks: Int,
        part: Part = Part.SUBTITLE,
        startFrame: Int = 0,
        interval: Int = 1,
        delay: Long = 0,
    ): Boolean {
        val key = runCatching { Key.key(font) }.getOrNull() ?: return false
        val slot = player.uniqueId to part
        running.remove(slot)?.cancel()
        val step = interval.coerceAtLeast(1)
        val frames = (durationTicks / step).coerceAtLeast(1)
        var shown = 0
        var task: BukkitTask? = null
        task = Bukkit.getScheduler().runTaskTimer(SneakyUtils.instance, Runnable {
            if (!player.isOnline || shown >= frames) {
                task?.cancel()
                if (running[slot] === task) running.remove(slot)
                if (player.isOnline) clear(player, part)
                return@Runnable
            }
            show(player, part, Component.text(frame(startFrame + shown)).font(key))
            shown++
        }, delay.coerceAtLeast(0), step.toLong())
        running[slot] = task
        return true
    }

    /** Stops the animation playing on [part] of [player]'s screen, if any, and clears that part. */
    @JvmStatic
    fun stop(player: Player, part: Part) {
        running.remove(player.uniqueId to part)?.let {
            it.cancel()
            clear(player, part)
        }
    }

    /** The glyph for frame [n], numbered as MagicSpells numbered them (see the class doc). */
    @JvmStatic
    fun frame(n: Int): String = Integer.parseInt("E%03d".format(Math.floorMod(n, 1000)), 16).toChar().toString()

    private fun show(player: Player, part: Part, glyph: Component) {
        when (part) {
            Part.ACTION_BAR -> player.sendActionBar(glyph)
            Part.TITLE -> player.showTitle(Title.title(glyph, Component.empty(), TIMES))
            Part.SUBTITLE -> player.showTitle(Title.title(Component.empty(), glyph, TIMES))
        }
    }

    private fun clear(player: Player, part: Part) {
        if (part == Part.ACTION_BAR) player.sendActionBar(Component.empty()) else player.clearTitle()
    }
}
