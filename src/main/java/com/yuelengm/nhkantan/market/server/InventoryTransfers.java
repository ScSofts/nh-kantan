package com.yuelengm.nhkantan.market.server;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.yuelengm.nhkantan.market.order.ExchangeRuntime;
import com.yuelengm.nhkantan.util.PlayerInventories;

/** Main-thread escrow journal coordinating atomic market checkpoints with actual player inventories. */
public final class InventoryTransfers {

    public interface Persistence {

        /** Persist this escrow map together with the market worker's completed save barrier. */
        void save(Map<UUID, Escrow> escrows) throws IOException;
    }

    public static final class Escrow {

        public final long id;
        public final long delta;
        public final boolean repayment;
        public final String before;
        public final String after;

        private Escrow(long id, long delta, boolean repayment, String before, String after) {
            this.id = id;
            this.delta = delta;
            this.repayment = repayment;
            this.before = before;
            this.after = after;
        }
    }

    private final MinecraftServer server;
    private final ExchangeRuntime runtime;
    private final Persistence persistence;
    private final Map<UUID, Escrow> pending = new HashMap<>();

    public InventoryTransfers(MinecraftServer server, ExchangeRuntime runtime, Persistence persistence,
        Map<UUID, Escrow> restored) {
        this.server = server;
        this.runtime = runtime;
        this.persistence = persistence;
        if (restored == null || restored.size() > 100000)
            throw new IllegalArgumentException("Invalid inventory journal");
        for (Map.Entry<UUID, Escrow> entry : restored.entrySet()) {
            Escrow escrow = entry.getValue();
            if (entry.getKey() == null || escrow == null
                || escrow.id < 1
                || escrow.delta == 0
                || escrow.before == null
                || escrow.after == null) throw new IllegalArgumentException("Invalid inventory escrow");
        }
        pending.putAll(restored);
    }

    public Map<UUID, Escrow> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(pending));
    }

    public boolean transfer(EntityPlayerMP player, String afterInventory, long delta, boolean repayment)
        throws IOException {
        UUID owner = player.getUniqueID();
        if (pending.containsKey(owner)) throw new IOException("Inventory transfer awaits reconciliation");
        String before = PlayerInventoryStore.inventory(player);
        if (PlayerInventoryStore.matches(player, afterInventory))
            throw new IOException("Transfer does not change inventory");
        long id = await(runtime.account(owner)).nextTransfer;
        PlayerInventoryStore.save(server, player); // Establish the recoverable pre-transfer player state first.
        Escrow escrow = new Escrow(id, delta, repayment, before, afterInventory);
        pending.put(owner, escrow);
        persistence.save(snapshot()); // Intent must exist before the worker changes any cash.
        if (!await(runtime.prepareTransfer(owner, id, delta, repayment))) {
            finishJournal(owner);
            return false;
        }
        persistence.save(snapshot()); // Prepared cash/frozen funds are now durable, inventory is still unchanged.
        PlayerInventoryStore.apply(player, afterInventory);
        PlayerInventoryStore.save(server, player);
        await(runtime.finishTransfer(owner, id, true));
        finishJournal(owner);
        PlayerInventories.resync(player);
        return true;
    }

    /** Run after a player's saved inventory has loaded, before accepting further wallet actions. */
    public void reconcile(EntityPlayerMP player) throws IOException {
        UUID owner = player.getUniqueID();
        Escrow escrow = pending.get(owner);
        if (escrow == null) return;
        boolean after = PlayerInventoryStore.matches(player, escrow.after);
        boolean before = PlayerInventoryStore.matches(player, escrow.before);
        if (!after && !before)
            throw new IOException("Escrow inventory mismatch; preserve records for manual reconciliation");
        boolean prepared = await(runtime.prepareTransfer(owner, escrow.id, escrow.delta, escrow.repayment));
        if (!prepared && after) throw new IOException("Transferred items have no accepted cash transaction");
        if (prepared) await(runtime.finishTransfer(owner, escrow.id, after));
        finishJournal(owner);
    }

    private void finishJournal(UUID owner) throws IOException {
        Map<UUID, Escrow> completed = new HashMap<>(pending);
        completed.remove(owner);
        persistence.save(completed); // If saving fails, keep the old in-memory journal available for recovery.
        pending.remove(owner);
    }

    public static <T> T await(CompletableFuture<T> operation) throws IOException {
        try {
            return operation.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted waiting for market", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // Never refund on timeout: the queued operation may still execute. Its durable ID is reconciled later.
            throw new IOException("Market command failed or is still pending", e);
        }
    }
}
