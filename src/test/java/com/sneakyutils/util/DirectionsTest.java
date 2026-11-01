package com.sneakyutils.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

final class DirectionsTest {

    private static final double EPS = 1e-4;

    @Test
    void dirForwardYawZeroIsSouth() {
        Vector d = Directions.dir(0, 0);
        assertEquals(0, d.getX(), EPS);
        assertEquals(0, d.getY(), EPS);
        assertEquals(1, d.getZ(), EPS);
    }

    @Test
    void dirYawNinetyIsWest() {
        Vector d = Directions.dir(90, 0);
        assertEquals(-1, d.getX(), EPS);
        assertEquals(0, d.getY(), EPS);
        assertEquals(0, d.getZ(), EPS);
    }

    @Test
    void clampToConeLeavesVectorAlreadyInCone() {
        Vector n = new Vector(0, 0, 1);
        Vector d = Directions.dir(20, 0).normalize();
        assertSame(d, Directions.clampToCone(d, n, 45));
    }

    @Test
    void clampToConeClampsVectorOutside() {
        Vector n = new Vector(0, 0, 1);
        Vector outside = Directions.dir(120, 0).normalize();
        double limit = 45;
        Vector clamped = Directions.clampToCone(outside, n, limit);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, clamped.dot(n)))));
        assertTrue(angle <= limit + 0.1, "angle to normal was " + angle);
        assertTrue(Math.abs(angle - limit) < 0.5, "should sit on the cone edge, was " + angle);
    }

    @Test
    void yawOfPitchOfRoundtrip() {
        float yaw = 37.5f, pitch = -12f;
        Vector d = Directions.dir(yaw, pitch);
        assertEquals(yaw, Directions.yawOf(d), 0.01f);
        assertEquals(pitch, Directions.pitchOf(d), 0.01f);
    }
}
