package net.silvertide.kindred.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.silvertide.kindred.Kindred;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class KindredSavedData extends SavedData {
    public static final String FILE_ID = "kindred_data";

    private final Map<UUID, Integer> revisionByBondId;
    private final Set<UUID> pendingDisbands;
    private final Set<UUID> killedWhileOffline;
    private final Map<UUID, OfflineSnapshot> offlineSnapshots;

    private KindredSavedData() {
        this(new HashMap<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), new HashMap<>());
    }

    private KindredSavedData(Map<UUID, Integer> revisions, Set<UUID> pending, Set<UUID> killed, Map<UUID, OfflineSnapshot> snapshots) {
        this.revisionByBondId = revisions;
        this.pendingDisbands = pending;
        this.killedWhileOffline = killed;
        this.offlineSnapshots = snapshots;
    }

    private record Snapshot(
            Map<UUID, Integer> revisionByBondId,
            Set<UUID> pendingDisbands,
            Set<UUID> killedWhileOffline,
            Map<UUID, OfflineSnapshot> offlineSnapshots
    ) {
        static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT)
                        .fieldOf("revisions")
                        .forGetter(Snapshot::revisionByBondId),
                UUIDUtil.STRING_CODEC.listOf()
                        .xmap(list -> (Set<UUID>) new LinkedHashSet<>(list), set -> new ArrayList<>(set))
                        .fieldOf("pending_disbands")
                        .forGetter(Snapshot::pendingDisbands),
                UUIDUtil.STRING_CODEC.listOf()
                        .xmap(list -> (Set<UUID>) new LinkedHashSet<>(list), set -> new ArrayList<>(set))
                        .fieldOf("killed_offline")
                        .forGetter(Snapshot::killedWhileOffline),
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, OfflineSnapshot.CODEC)
                        .fieldOf("offline_snapshots")
                        .forGetter(Snapshot::offlineSnapshots)
        ).apply(instance, Snapshot::new));
    }

    public static SavedData.Factory<KindredSavedData> factory() {
        return new SavedData.Factory<>(KindredSavedData::new, KindredSavedData::load, null);
    }

    public static KindredSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    public static KindredSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        Optional<Snapshot> parsed = Snapshot.CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(err -> Kindred.LOGGER.error("Failed to load {}: {}", FILE_ID, err));
        if (parsed.isEmpty()) return new KindredSavedData();
        Snapshot s = parsed.get();
        return new KindredSavedData(
                new HashMap<>(s.revisionByBondId()),
                new LinkedHashSet<>(s.pendingDisbands()),
                new LinkedHashSet<>(s.killedWhileOffline()),
                new HashMap<>(s.offlineSnapshots())
        );
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        Snapshot s = new Snapshot(revisionByBondId, pendingDisbands, killedWhileOffline, offlineSnapshots);
        Snapshot.CODEC.encodeStart(NbtOps.INSTANCE, s)
                .resultOrPartial(err -> Kindred.LOGGER.error("Failed to save {}: {}", FILE_ID, err))
                .ifPresent(t -> tag.merge((CompoundTag) t));
        return tag;
    }

    public int getRevision(UUID bondId) {
        return revisionByBondId.getOrDefault(bondId, 0);
    }

    public int incrementRevision(UUID bondId) {
        int next = getRevision(bondId) + 1;
        revisionByBondId.put(bondId, next);
        setDirty();
        return next;
    }

    public void clearBond(UUID bondId) {
        boolean changed = false;
        if (revisionByBondId.remove(bondId) != null) changed = true;
        if (pendingDisbands.remove(bondId)) changed = true;
        if (killedWhileOffline.remove(bondId)) changed = true;
        if (offlineSnapshots.remove(bondId) != null) changed = true;
        if (changed) setDirty();
    }

    public void markPendingDisband(UUID bondId) {
        if (pendingDisbands.add(bondId)) setDirty();
    }

    public boolean isPendingDisband(UUID bondId) {
        return pendingDisbands.contains(bondId);
    }

    public void clearPendingDisband(UUID bondId) {
        if (pendingDisbands.remove(bondId)) setDirty();
    }

    public void markKilledOffline(UUID bondId) {
        if (killedWhileOffline.add(bondId)) setDirty();
    }

    public boolean wasKilledOffline(UUID bondId) {
        return killedWhileOffline.contains(bondId);
    }

    public void clearKilledOffline(UUID bondId) {
        if (killedWhileOffline.remove(bondId)) setDirty();
    }

    public void putOfflineSnapshot(UUID bondId, OfflineSnapshot snapshot) {
        offlineSnapshots.put(bondId, snapshot);
        setDirty();
    }

    public Optional<OfflineSnapshot> takeOfflineSnapshot(UUID bondId) {
        OfflineSnapshot s = offlineSnapshots.remove(bondId);
        if (s != null) setDirty();
        return Optional.ofNullable(s);
    }
}
