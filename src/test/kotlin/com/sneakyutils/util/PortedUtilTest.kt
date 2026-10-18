package com.sneakyutils.util

import com.sneakyutils.util.io.AtomicFiles
import org.bukkit.Color
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.regex.Pattern

class PortedUtilTest {

    @Test
    fun booleanUtils_parsesYesNo() {
        assertTrue(BooleanUtils.isYes("yes"))
        assertTrue(BooleanUtils.isYes("true"))
        assertTrue(BooleanUtils.isNo("no"))
        assertFalse(BooleanUtils.isYes("maybe"))
    }

    @Test
    fun paramUtil_splitsQuotedArgs() {
        val parts = ParamUtil.splitParams("""hello "world with spaces" trailing""")
        assertArrayEquals(arrayOf("hello", "world with spaces", "trailing"), parts)
    }

    @Test
    fun timeUtil_constants() {
        assertEquals(20, TimeUtil.TICKS_PER_SECOND)
        assertEquals(1200, TimeUtil.TICKS_PER_MINUTE)
    }

    @Test
    fun colorUtil_parsesHexAndSectionNames() {
        assertEquals(Color.fromRGB(0xFF0000), ColorUtil.getColorFromHexString("#FF0000"))
        assertEquals(ColorUtil.RED, ColorUtil.sectionCodeFromName("red", ColorUtil.WHITE))
        assertEquals("hello", ColorUtil.stripColor(ColorUtil.colorize("&chello")))
    }

    @Test
    fun potionIdentity_canonicalizesLegacyFlags() {
        assertEquals("LONG_SWIFTNESS", PotionIdentity.parse("swiftness extended"))
        assertEquals("STRONG_SWIFTNESS", PotionIdentity.parse("swiftness upgraded"))
        assertThrows(IllegalArgumentException::class.java) {
            PotionIdentity.parse("LONG_SWIFTNESS extended")
        }
    }

    @Test
    fun attributeSpecParser_parsesLine() {
        val spec = AttributeSpecParser.parse("generic.max_health 2 add_number", false)
        assertEquals("generic.max_health", spec.attributeName)
        assertEquals(2.0, spec.amount, 0.0)
        assertEquals("add_number", spec.operationName)
    }

    @Test
    fun intMap_storesValues() {
        val map = IntMap<String>()
        map.put("a", 3)
        map.increment("a")
        assertEquals(4, map.get("a"))
        assertTrue(map.containsKey("a"))
    }

    @Test
    fun regexUtil_matchesSafely() {
        assertTrue(RegexUtil.matches(Pattern.compile("a.c"), "abc"))
        assertFalse(RegexUtil.matches(Pattern.compile("a.c"), "ab"))
    }

    @Test
    fun atomicFiles_writesUtf8() {
        val dir = Files.createTempDirectory("sneakyutils-atomic")
        val target = dir.resolve("out.txt")
        AtomicFiles.writeUtf8(target, "hello-sneakyutils")
        assertEquals("hello-sneakyutils", Files.readString(target))
    }

    @Test
    fun experienceUtils_levelFromExp() {
        assertEquals(0, ExperienceUtils.getLevelFromExp(0))
        assertTrue(ExperienceUtils.getLevelFromExp(100) > 0)
    }

    @Test
    fun particleUtil_resolvesAlias() {
        assertNotNull(ParticleUtil.getParticle("poof"))
        assertNull(ParticleUtil.getParticle("not_a_real_particle_xyz"))
    }

    @Test
    fun soundKeyNames_normalize() {
        assertEquals("minecraft:entity.player.levelup", SoundKeyNames.toNamespacedKeyString("entity.player.levelup"))
    }
}
