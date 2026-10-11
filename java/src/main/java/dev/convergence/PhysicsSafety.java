package dev.convergence;

import net.minecraft.world.phys.Vec3;

/** Bound work performed by native collision queries without changing stored admin attributes. */
public final class PhysicsSafety {
    public static final double MAX_MOTION = 32;
    public static final double MAX_KNOCKBACK_IMPULSE = 8;
    private PhysicsSafety() {}

    public static double knockback(double strength) {
        return Double.isFinite(strength) ? Math.max(0, Math.min(MAX_KNOCKBACK_IMPULSE, strength)) : 0;
    }

    public static Vec3 motion(Vec3 value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) return Vec3.ZERO;
        double peak = Math.max(Math.abs(value.x), Math.max(Math.abs(value.y), Math.abs(value.z)));
        if (peak <= MAX_MOTION / Math.sqrt(3)) return value;
        // Normalize in two stages to avoid overflow even for finite double extremes.
        var unit = value.scale(1 / peak);
        double length = unit.length();
        if (peak <= MAX_MOTION / length) return value;
        return unit.scale(MAX_MOTION / length);
    }
}
