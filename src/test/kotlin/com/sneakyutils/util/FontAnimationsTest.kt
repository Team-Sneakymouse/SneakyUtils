package com.sneakyutils.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FontAnimationsTest {

    @Test
    fun framesUseMagicSpellsDecimalNumbering() {
        assertEquals("\uE000", FontAnimations.frame(0))
        assertEquals("\uE009", FontAnimations.frame(9))
        assertEquals("\uE010", FontAnimations.frame(10))
        assertEquals("\uE034", FontAnimations.frame(34))
        assertEquals("\uE999", FontAnimations.frame(999))
    }

    @Test
    fun framesWrapAfter999() {
        assertEquals("\uE000", FontAnimations.frame(1000))
        assertEquals("\uE999", FontAnimations.frame(-1))
    }
}
