package com.sneakyutils.util;

import org.bukkit.util.Vector;
import org.joml.Quaternionf;

/** Yaw/pitch, look cones and aiming, in Minecraft's angles (from SneakyCCTV, shared since the watch engine moved here). */
public final class Directions {

    private Directions() {}

    /** The unit vector a yaw and pitch look along (Minecraft's: yaw 0 = south, pitch 90 = down). */
    public static Vector dir(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    public static float yawOf(Vector d) {
        return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    public static float pitchOf(Vector d) {
        return (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.getY() / d.length()))));
    }

    /**
     * The direction nearest to d that's within limit degrees of n (both unit vectors): d itself if it already is,
     * else the edge of the cone in d's direction.
     */
    public static Vector clampToCone(Vector d, Vector n, double limit) {
        double cos = d.dot(n);
        double a = Math.toRadians(limit);
        if (cos >= Math.cos(a) - 1e-4) return d;
        Vector perp = d.clone().subtract(n.clone().multiply(cos));
        if (perp.lengthSquared() < 1e-9) {                      // straight back: pick any way round
            perp = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
            perp.subtract(n.clone().multiply(perp.dot(n)));
        }
        perp.normalize();
        return n.clone().multiply(Math.cos(a)).add(perp.multiply(Math.sin(a))).normalize();
    }

    /**
     * Rotation that turns the model's forward (-Z, its north) to look along yaw/pitch, upright. An item display turns its
     * item half round (its -Z to +Z) before this rotation, so this is the one that takes +Z there.
     */
    public static Quaternionf aim(float yaw, float pitch) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch));
    }

    /** Wrap an angle in degrees to (-180, 180]. */
    public static float wrap(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }
}
