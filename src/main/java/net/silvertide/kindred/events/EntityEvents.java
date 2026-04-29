package net.silvertide.kindred.events;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.silvertide.kindred.Kindred;
import net.silvertide.kindred.attachment.Bond;
import net.silvertide.kindred.attachment.BondRoster;
import net.silvertide.kindred.attachment.Bonded;
import net.silvertide.kindred.config.Config;
import net.silvertide.kindred.registry.ModAttachments;
import net.silvertide.kindred.bond.BondIndex;
import net.silvertide.kindred.bond.BondService;
import net.silvertide.kindred.data.OfflineSnapshot;
import net.silvertide.kindred.data.KindredSavedData;

import java.util.Optional;

@EventBusSubscriber(modid = Kindred.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EntityEvents {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (!entity.hasData(ModAttachments.BONDED.get())) return;

        Bonded bonded = entity.getData(ModAttachments.BONDED.get());
        KindredSavedData saved = KindredSavedData.get(level);

        // Pending disband: bond was broken while this entity was unloaded.
        // Strip its bonded attachment and let it join as a normal entity.
        if (saved.isPendingDisband(bonded.bondId())) {
            entity.removeData(ModAttachments.BONDED.get());
            saved.clearPendingDisband(bonded.bondId());
            saved.clearBond(bonded.bondId());
            return;
        }

        // Anti-dupe: if this entity carries a stale revision, it's a duplicate of one
        // that was re-materialized elsewhere. Cancel the join.
        int worldRevision = saved.getRevision(bonded.bondId());
        if (bonded.revision() < worldRevision) {
            event.setCanceled(true);
            Kindred.LOGGER.info("[kindred] cancelled stale duplicate of bond {} (entity rev {} < world rev {})",
                    bonded.bondId(), bonded.revision(), worldRevision);
            return;
        }

        BondIndex.get().track(bonded.bondId(), entity);
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (!entity.hasData(ModAttachments.BONDED.get())) return;

        Bonded bonded = entity.getData(ModAttachments.BONDED.get());
        BondIndex.get().untrack(bonded.bondId(), entity);

        snapshotEntity(level, entity, bonded);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!entity.hasData(ModAttachments.BONDED.get())) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        Bonded bonded = entity.getData(ModAttachments.BONDED.get());

        if (Config.DEATH_IS_PERMANENT.get()) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(bonded.ownerUUID());
            if (owner != null) {
                BondService.breakBond(owner, bonded.bondId());
            } else {
                KindredSavedData.get(level).markKilledOffline(bonded.bondId());
            }
            return;
        }

        // Non-permanent death: capture a "death snapshot" of the entity BEFORE vanilla's
        // dropAllDeathLoot runs. This is the source of truth for revival.
        //
        // Why pre-drop: vanilla's dropEquipment is inconsistent. Mob.dropEquipment clears
        // EquipmentSlot.BODY (horse body armor) after capturing for LivingDropsEvent, but
        // AbstractHorse.dropEquipment leaves inventory[0/saddle] and chest items in place
        // even after iterating them into the captured drops. If we snapshot post-death
        // (at EntityLeaveLevelEvent), with dropLootOnDeath=false we'd lose armor and keep
        // saddle (half-restoration); with dropLootOnDeath=true the world gets the saddle
        // AND the entity keeps it (double-grant). Snapshotting before vanilla touches
        // anything sidesteps both bugs — we then strip the snapshot only when items
        // genuinely drop to the world.
        //
        // diedAt is also set here so EntityLeaveLevelEvent's snapshotEntity knows to skip
        // overwriting our death snapshot with the post-drop carcass state. The revival
        // cooldown gate in BondService.summon ignores diedAt when revivalCooldownMs=0,
        // so this is safe to set regardless of cooldown config.
        CompoundTag deathNbt = entity.saveWithoutId(new CompoundTag());
        if (Config.DROP_LOOT_ON_DEATH.get()) {
            stripItemsFromSnapshot(deathNbt);
        }

        long now = System.currentTimeMillis();
        ResourceKey<Level> dim = level.dimension();
        Vec3 pos = entity.position();

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(bonded.ownerUUID());
        if (owner != null) {
            BondRoster roster = owner.getData(ModAttachments.BOND_ROSTER.get());
            roster.get(bonded.bondId()).ifPresent(b -> {
                Bond updated = b.withSnapshot(deathNbt, dim, pos)
                                .withDiedAt(Optional.of(now));
                owner.setData(ModAttachments.BOND_ROSTER.get(), roster.with(updated));
            });
        } else {
            // Offline owner: persist the pre-drop snapshot to the world's offline cache.
            // diedAt isn't tracked for offline owners (known follow-up); revival cooldown
            // won't apply to a pet whose owner was offline at time of death.
            KindredSavedData.get(level).putOfflineSnapshot(bonded.bondId(),
                    new OfflineSnapshot(deathNbt, dim, pos));
        }
    }

    /**
     * Clear every item-storage NBT key we know about, leaving the entity's "self"
     * (variant, age, attributes, custom name, AI state) intact. Used when
     * {@code dropLootOnDeath=true} so the world keeps the dropped items and the
     * revived pet comes back empty.
     *
     * <p>Hand-rolled because vanilla doesn't expose an "empty all items" helper and
     * 1.21 stores equipment across several distinct keys depending on entity type:
     * Mob's {@code ArmorItems} / {@code HandItems} lists, AbstractHorse's
     * {@code Items} list (saddle + chest), and the BodyArmor item slot. Adding
     * a missing key is cheap; missing one means a stray item rides through revival.</p>
     */
    private static void stripItemsFromSnapshot(CompoundTag nbt) {
        nbt.remove("SaddleItem");       // AbstractHorse saddle — separate top-level compound, NOT in Items
        nbt.remove("Items");            // AbstractChestedHorse chest contents (slots 1+)
        nbt.remove("ArmorItems");       // Mob armor slots (head/chest/legs/feet)
        nbt.remove("HandItems");        // Mob hand slots (mainhand/offhand)
        nbt.remove("body_armor_item");  // 1.21 horse / llama / wolf body armor
        nbt.remove("ChestedHorse");     // donkey/llama chest flag — chest gone, flag should be too
    }

    /**
     * Suppress vanilla loot/inventory drops for bonded pets when the config opts out.
     * Pairs with the revival cooldown — without this, a horse with a saddle and chest
     * full of gear would scatter all of it on death and the revived pet would come
     * back empty. Cancels the whole drops list (mob loot, equipment, chest contents,
     * wolf armor) since the snapshot taken at corpse-removal still contains them.
     */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (Config.DROP_LOOT_ON_DEATH.get()) return;
        if (!event.getEntity().hasData(ModAttachments.BONDED.get())) return;
        event.setCanceled(true);
    }

    /**
     * Same opt-out also suppresses XP orbs from bonded-pet deaths. Reviving a pet
     * shouldn't be a renewable XP source either.
     */
    @SubscribeEvent
    public static void onLivingExperienceDrop(LivingExperienceDropEvent event) {
        if (Config.DROP_LOOT_ON_DEATH.get()) return;
        if (!event.getEntity().hasData(ModAttachments.BONDED.get())) return;
        event.setCanceled(true);
    }

    private static void snapshotEntity(ServerLevel level, Entity entity, Bonded bonded) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(bonded.ownerUUID());

        // If the bond already has a death snapshot (captured at LivingDeathEvent before
        // vanilla touched equipment), don't overwrite it with the post-drop carcass.
        // This handler also fires for chunk unloads / dimension changes during the
        // ~20-tick death animation, and for the discard at the end of it — none of which
        // should clobber the authoritative pre-drop snapshot.
        if (owner != null) {
            BondRoster preCheck = owner.getData(ModAttachments.BOND_ROSTER.get());
            if (preCheck.get(bonded.bondId()).flatMap(Bond::diedAt).isPresent()) return;
        }

        CompoundTag nbt = entity.saveWithoutId(new CompoundTag());
        ResourceKey<Level> dim = level.dimension();
        Vec3 pos = entity.position();

        if (owner != null) {
            BondRoster roster = owner.getData(ModAttachments.BOND_ROSTER.get());
            Optional<Bond> bond = roster.get(bonded.bondId());
            if (bond.isPresent()) {
                Bond updated = bond.get().withSnapshot(nbt, dim, pos);
                // Carry through any customName change made via vanilla nametag while
                // the pet was loaded — keeps the roster in sync with what's in-world.
                Optional<String> currentName = Optional.ofNullable(entity.getCustomName())
                        .map(Component::getString)
                        .filter(s -> !s.isEmpty());
                if (!currentName.equals(updated.displayName())) {
                    updated = updated.withDisplayName(currentName);
                }
                owner.setData(ModAttachments.BOND_ROSTER.get(), roster.with(updated));
            }
        } else {
            KindredSavedData.get(level).putOfflineSnapshot(bonded.bondId(), new OfflineSnapshot(nbt, dim, pos));
        }
    }

    private EntityEvents() {}
}
