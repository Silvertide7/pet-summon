package net.silvertide.kindred.events;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired on the NeoForge game event bus when a bond claim is about to commit.
 * Listeners can {@link #setCanceled(boolean) cancel} to reject the claim — the
 * player sees a "binding cancelled" message and no bond writes happen.
 *
 * <p>Fires on the server only, after {@code BondManager.checkClaimEligibility}
 * has returned {@code CLAIMED}, so listeners can assume the basic gates
 * (ownership, blocklist, capacity, XP, PMMO) already passed. Use this hook for
 * custom gates: quest progress, party rules, datapack predicates, KubeJS
 * scripts, etc.</p>
 *
 * <p>Fires once per claim attempt. Read-only "should I allow this?" logic is
 * the intended use; side-effects (logging, decrementing counters) are fine but
 * should be idempotent in case a downstream listener cancels after yours runs.</p>
 */
public class BondClaimEvent extends Event implements ICancellableEvent {
    private final ServerPlayer player;
    private final Entity target;

    public BondClaimEvent(ServerPlayer player, Entity target) {
        this.player = player;
        this.target = target;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public Entity getTarget() {
        return target;
    }
}
