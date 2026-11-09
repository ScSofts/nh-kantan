package com.yuelengm.nhkantan.market.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.MapStorage;

import com.yuelengm.nhkantan.market.order.LegacyEquityMigration;
import com.yuelengm.nhkantan.trading.Position;
import com.yuelengm.nhkantan.trading.PositionSavedData;
import com.yuelengm.nhkantan.wallet.WalletSavedData;

/** Main-thread migration adapter. Clearing requires a verified, already-committed realistic save. */
public final class LegacyMigration {

    private LegacyMigration() {}

    public static Map<UUID, Long> calculate(MinecraftServer server) throws IOException {
        MapStorage storage = server.worldServerForDimension(0).mapStorage;
        Path world = server.worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory()
            .toPath();
        WalletSavedData wallets = (WalletSavedData) storage.loadData(WalletSavedData.class, WalletSavedData.DATA_NAME);
        PositionSavedData positions = (PositionSavedData) storage
            .loadData(PositionSavedData.class, PositionSavedData.DATA_NAME);
        if ((wallets == null && Files.exists(world.resolve("data/" + WalletSavedData.DATA_NAME + ".dat")))
            || (positions == null && Files.exists(world.resolve("data/" + PositionSavedData.DATA_NAME + ".dat")))) {
            throw new IOException("Legacy financial data exists but could not be read; refusing migration");
        }
        Map<UUID, Long> cash = wallets == null ? Collections.emptyMap() : wallets.snapshot();
        Map<UUID, List<Position>> held = positions == null ? Collections.emptyMap() : positions.snapshot();
        Map<String, Double> prices = new HashMap<>();
        FilePriceStore history = new FilePriceStore(world.resolve("nhkantan/prices"));
        for (List<Position> owned : held.values()) {
            for (Position position : owned) {
                String id = position.stockId();
                if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid legacy stock ID");
                if (!prices.containsKey(id)) {
                    float[] tail = history.readTail(id, 1);
                    if (tail.length != 1) throw new IOException("No valid legacy settlement price for " + id);
                    prices.put(id, (double) tail[0]);
                }
            }
        }
        return LegacyEquityMigration.calculate(cash, held, prices);
    }

    /** Idempotent cleanup, only after the new checkpoint has been re-read and its migration marker checked. */
    public static void clearRecords(Path world, AtomicMarketStore committedStore) throws IOException {
        RealisticSavedData committed = committedStore.load();
        if (committed == null || !committed.migrated()) throw new IOException("No committed net-equity migration");
        Files.deleteIfExists(world.resolve("data/" + WalletSavedData.DATA_NAME + ".dat"));
        Files.deleteIfExists(world.resolve("data/" + PositionSavedData.DATA_NAME + ".dat"));
        Path history = world.resolve("nhkantan/prices");
        if (Files.exists(history)) {
            List<Path> paths;
            try (Stream<Path> stream = Files.walk(history)) {
                paths = stream.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            }
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
}
