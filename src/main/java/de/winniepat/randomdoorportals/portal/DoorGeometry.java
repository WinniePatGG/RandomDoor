package de.winniepat.randomdoorportals.portal;

/**
 * Pure, Minecraft-independent geometry and key encoding helpers for door portals.
 *
 * <p>Everything in this class is deterministic math over doubles/longs so it can be
 * unit tested without Minecraft classes on the classpath.</p>
 *
 * <h2>Door key layout (64 bits)</h2>
 * <pre>
 * bits 63..38  x  (26 bits, two's complement)
 * bits 37..12  z  (26 bits, two's complement)
 * bits 11..0   y  (12 bits, two's complement, range -2048..2047)
 * </pre>
 */
public final class DoorGeometry {
    /** Minimum |distance| between the previous position and the plane for a valid crossing. */
    public static final double PLANE_EPSILON = 1.0E-4;

    /** Height band (relative to the door's lower block) in which a crossing counts as through the doorway. */
    private static final double HEIGHT_BELOW = 0.45;
    private static final double HEIGHT_ABOVE = 2.25;
    /** Horizontal inset (blocks) the crossing point must keep from the doorway cell walls. */
    private static final double DOORWAY_INSET = 0.05;

    private DoorGeometry() {
    }

    // ------------------------------------------------------------------ keys

    /** Encodes a lower door block position into a single long. */
    public static long encodeDoorKey(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    public static int doorKeyX(long key) {
        return (int) (key >> 38);
    }

    public static int doorKeyY(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int doorKeyZ(long key) {
        return (int) (key << 26 >> 38);
    }

    /**
     * Packs a chunk coordinate pair into one long (lower 32 bits = z, upper 32 bits = x),
     * compatible with {@code ChunkPos#toLong}.
     */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) << 32 | ((long) chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkKeyZ(long key) {
        return (int) key;
    }

    // -------------------------------------------------------------- crossing

    /**
     * True when the segment {@code [prev, next]} crosses the plane {@code plane}.
     *
     * <p>The starting point must be at least {@link #PLANE_EPSILON} away from the plane so a
     * player standing exactly on the plane does not re-trigger the crossing every tick.
     * Reaching the plane exactly counts as crossing (the player center entered the doorway).</p>
     *
     * @param prev  previous coordinate along the door axis
     * @param next  new coordinate along the door axis
     * @param plane plane coordinate along the same axis
     */
    public static boolean crossesPlane(double prev, double next, double plane) {
        double dPrev = prev - plane;
        if (Math.abs(dPrev) < PLANE_EPSILON) {
            return false;
        }
        double dNext = next - plane;
        if (dPrev > 0.0) {
            return dNext <= 0.0;
        }
        return dNext >= 0.0;
    }

    /**
     * Fraction {@code t} of the segment {@code [prev, next]} at which {@code plane} is crossed,
     * or {@code -1} when the segment never reaches the plane.
     */
    public static double crossingFraction(double prev, double next, double plane) {
        double span = next - prev;
        if (Math.abs(span) < 1.0E-12) {
            return -1.0;
        }
        double t = (plane - prev) / span;
        if (t < 0.0 || t > 1.0) {
            return -1.0;
        }
        return t;
    }

    /**
     * True when a horizontal coordinate lies strictly inside the doorway cell
     * (perpendicular coordinate, e.g. the x coordinate for a north/south door).
     */
    public static boolean withinDoorwaySpan(double perpCoord, double cellBase) {
        return perpCoord > cellBase + DOORWAY_INSET && perpCoord < cellBase + 1.0 - DOORWAY_INSET;
    }

    /**
     * True when the y coordinate at the crossing point is inside the doorway height band
     * (doorways are two blocks tall starting at {@code lowerBlockY}).
     */
    public static boolean withinDoorwayHeight(double y, double lowerBlockY) {
        return y >= lowerBlockY - HEIGHT_BELOW && y <= lowerBlockY + HEIGHT_ABOVE;
    }
}
