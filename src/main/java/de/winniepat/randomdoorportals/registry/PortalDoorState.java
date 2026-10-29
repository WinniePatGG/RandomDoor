package de.winniepat.randomdoorportals.registry;

import de.winniepat.randomdoorportals.portal.DoorGeometry;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Persistent, per-dimension store of portal door positions.
 *
 * <p>Format (NBT, one file per dimension under {@code data/<dimension>/}):</p>
 * <pre>
 *   Doors: [L; long-encoded lower door block positions]
 * </pre>
 *
 * <p>The runtime chunk index ({@code byChunk}) is rebuilt from the set after loading, so the
 * on-disk format stays minimal. Door keys are lower block positions encoded by
 * {@link DoorGeometry#encodeDoorKey(int, int, int)}.</p>
 *
 * <p>{@code DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES} is used as a placeholder so the required
 * non-null fixer parameter has a value; our data only contains a plain long array that no
 * {@code RandomSequences} fixer touches, and same-version loads never run fixers at all.</p>
 */
public class PortalDoorState extends PersistentState {
    public static final String STATE_ID = "randomdoorportals_doors";

    private static final String DOORS_KEY = "Doors";

    public static final PersistentState.Type<PortalDoorState> STATE_TYPE = new PersistentState.Type<>(
            PortalDoorState::new, PortalDoorState::fromNbt, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final LinkedHashSet<Long> doors = new LinkedHashSet<>();
    private final Map<Long, LinkedHashSet<Long>> byChunk = new HashMap<>();

    // ------------------------------------------------------------ persistence

    /** Deserializes a saved state (called once per dimension on world load). */
    public static PortalDoorState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        PortalDoorState state = new PortalDoorState();
        state.doors.clear();
        for (long key : nbt.getLongArray(DOORS_KEY)) {
            state.doors.add(key);
        }
        state.rebuildChunkIndex();
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        long[] values = new long[doors.size()];
        int index = 0;
        for (long key : doors) {
            values[index++] = key;
        }
        nbt.putLongArray(DOORS_KEY, values);
        return nbt;
    }

    // ---------------------------------------------------------------- runtime

    private void rebuildChunkIndex() {
        byChunk.clear();
        for (long key : doors) {
            indexDoor(key);
        }
    }

    private void indexDoor(long key) {
        long chunkKey = DoorGeometry.chunkKey(DoorGeometry.doorKeyX(key) >> 4, DoorGeometry.doorKeyZ(key) >> 4);
        byChunk.computeIfAbsent(chunkKey, k -> new LinkedHashSet<>()).add(key);
    }

    /** Adds a door; returns false when it was already registered. */
    public boolean add(long key) {
        if (doors.contains(key)) {
            return false;
        }
        doors.add(key);
        indexDoor(key);
        markDirty();
        return true;
    }

    /** Removes a door (e.g. broken, or stale after a chunk rescan); returns false when unknown. */
    public boolean remove(long key) {
        if (!doors.contains(key)) {
            return false;
        }
        doors.remove(key);
        long chunkKey = DoorGeometry.chunkKey(DoorGeometry.doorKeyX(key) >> 4, DoorGeometry.doorKeyZ(key) >> 4);
        LinkedHashSet<Long> inChunk = byChunk.get(chunkKey);
        if (inChunk != null) {
            inChunk.remove(key);
            if (inChunk.isEmpty()) {
                byChunk.remove(chunkKey);
            }
        }
        markDirty();
        return true;
    }

    public boolean contains(long key) {
        return doors.contains(key);
    }

    public int size() {
        return doors.size();
    }

    public boolean isEmpty() {
        return doors.isEmpty();
    }

    public Iterator<Long> iterator() {
        return doors.iterator();
    }

    /** Point-in-time copy for iteration that may remove entries. */
    public List<Long> snapshot() {
        return new ArrayList<>(doors);
    }

    /** Appends all registered door keys of the given chunk to {@code out}. */
    public void doorsInChunk(int chunkX, int chunkZ, List<Long> out) {
        LinkedHashSet<Long> inChunk = byChunk.get(DoorGeometry.chunkKey(chunkX, chunkZ));
        if (inChunk != null) {
            out.addAll(inChunk);
        }
    }
}
