package de.winniepat.randomdoorportals.portal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorGeometryTest {

    // ------------------------------------------------------------- key coding

    @Test
    void encodeDecodeRoundTrip() {
        assertRoundTrip(0, 0, 0);
        assertRoundTrip(123, 64, -456);
        assertRoundTrip(-1, -64, -1);
        assertRoundTrip(1, 320, 1);
        assertRoundTrip(-1234, -60, 4321);
        assertRoundTrip(29_999_984, -64, -29_999_984);
        assertRoundTrip(1023, -2047, -1000);
    }

    private void assertRoundTrip(int x, int y, int z) {
        long key = DoorGeometry.encodeDoorKey(x, y, z);
        assertEquals(x, DoorGeometry.doorKeyX(key));
        assertEquals(y, DoorGeometry.doorKeyY(key));
        assertEquals(z, DoorGeometry.doorKeyZ(key));
    }

    @Test
    void keysAreDistinctPerBlock() {
        long base = DoorGeometry.encodeDoorKey(10, 20, 30);
        assertFalse(base == DoorGeometry.encodeDoorKey(11, 20, 30));
        assertFalse(base == DoorGeometry.encodeDoorKey(10, 21, 30));
        assertFalse(base == DoorGeometry.encodeDoorKey(10, 20, 31));
    }

    @Test
    void chunkKeyRoundTrip() {
        long key = DoorGeometry.chunkKey(-3, 7);
        assertEquals(-3, DoorGeometry.chunkKeyX(key));
        assertEquals(7, DoorGeometry.chunkKeyZ(key));
        long key2 = DoorGeometry.chunkKey(Integer.MIN_VALUE / 16, Integer.MAX_VALUE / 16);
        assertEquals(Integer.MIN_VALUE / 16, DoorGeometry.chunkKeyX(key2));
        assertEquals(Integer.MAX_VALUE / 16, DoorGeometry.chunkKeyZ(key2));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,0", "500,31,-500,-32", "-500,-32,500,31", "-500,-32,-500,-32"})
    void floorDivisionChunkIndex(int x, int expectedChunkX, int z, int expectedChunkZ) {
        long key = DoorGeometry.encodeDoorKey(x, 0, z);
        assertEquals(expectedChunkX, DoorGeometry.doorKeyX(key) >> 4);
        assertEquals(expectedChunkZ, DoorGeometry.doorKeyZ(key) >> 4);
    }

    // -------------------------------------------------------------- crossings

    @Test
    void crossingDetectsBothDirections() {
        double plane = 10.5;
        assertTrue(DoorGeometry.crossesPlane(10.6, 10.4, plane)); // walking down
        assertTrue(DoorGeometry.crossesPlane(10.4, 10.6, plane)); // walking up
        assertFalse(DoorGeometry.crossesPlane(10.6, 10.7, plane)); // staying above
        assertFalse(DoorGeometry.crossesPlane(10.3, 10.2, plane)); // staying below
        assertFalse(DoorGeometry.crossesPlane(10.5, 10.6, plane)); // start exactly on plane
    }

    @Test
    void crossingIgnoresStandingNearPlane() {
        double plane = 10.5;
        // Jittering exactly around the plane start - never triggers.
        assertFalse(DoorGeometry.crossesPlane(plane, plane - 1.0E-5, plane));
        assertFalse(DoorGeometry.crossesPlane(plane + 1.0E-5, plane, plane));
        assertFalse(DoorGeometry.crossesPlane(plane - 1.0E-5, plane + 1.0E-5, plane));
        // Crossing far away from the plane is not a crossing.
        assertFalse(DoorGeometry.crossesPlane(12.0, 14.0, plane));
    }

    @Test
    void reachingPlaneExactlyCountsAsCrossed() {
        double plane = 10.5;
        assertTrue(DoorGeometry.crossesPlane(10.7, 10.5, plane));
        assertTrue(DoorGeometry.crossesPlane(10.3, 10.5, plane));
    }

    @Test
    void crossingFraction() {
        assertEquals(0.5, DoorGeometry.crossingFraction(10.0, 11.0, 10.5), 1.0E-12);
        assertEquals(0.0, DoorGeometry.crossingFraction(10.5, 11.0, 10.5), 1.0E-12);
        assertEquals(-1.0, DoorGeometry.crossingFraction(11.0, 12.0, 10.5), 1.0E-12);
        assertEquals(-1.0, DoorGeometry.crossingFraction(10.0, 10.0, 10.5), 1.0E-12);
        // negative direction
        assertEquals(0.5, DoorGeometry.crossingFraction(11.0, 10.0, 10.5), 1.0E-12);
    }

    // ------------------------------------------------------------- spans

    @Test
    void doorwaySpanChecks() {
        int lowerZ = 100; // doorway cell 100..101, crossing along x
        assertTrue(DoorGeometry.withinDoorwaySpan(100.5, lowerZ));
        assertTrue(DoorGeometry.withinDoorwaySpan(100.1, lowerZ));
        assertTrue(DoorGeometry.withinDoorwaySpan(100.9, lowerZ));
        // Adjacent cell (walking past the wall next to the door) is not a doorway crossing.
        assertFalse(DoorGeometry.withinDoorwaySpan(101.5, lowerZ));
        assertFalse(DoorGeometry.withinDoorwaySpan(99.7, lowerZ));
        assertFalse(DoorGeometry.withinDoorwaySpan(101.0, lowerZ));
    }

    @Test
    void doorwayHeightChecks() {
        double lowerY = 64;
        assertTrue(DoorGeometry.withinDoorwayHeight(64.0, lowerY));
        assertTrue(DoorGeometry.withinDoorwayHeight(65.7, lowerY)); // falling through, feet halfway up
        assertTrue(DoorGeometry.withinDoorwayHeight(63.6, lowerY)); // stepping slightly below
        // Far above the lintel or far below the floor: not through the doorway.
        assertFalse(DoorGeometry.withinDoorwayHeight(66.5, lowerY));
        assertFalse(DoorGeometry.withinDoorwayHeight(63.0, lowerY));
    }
}
