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

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
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
        if (positions == null && Files.exists(world.resolve("data/" + PositionSavedData.DATA_NAME + ".dat"))) {
            throw new IOException("Legacy financial data exists but could not be read; refusing migration");
        }
        Map<UUID, Long> cash = wallets == null ? Collections.emptyMap() : wallets.snapshot();
        Path walletFile = world.resolve("data/" + WalletSavedData.DATA_NAME + ".dat");
        if (Files.exists(walletFile)) cash = readWallets(walletFile);
        backupLegacy(world);
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

    /** Read the actual MapStorage NBT wrapper, including old whole-unit balances. Never treat unknown data as zero. */
    public static Map<UUID, Long> readWallets(Path file) throws IOException {
        if (Files.size(file) > 32 * 1024 * 1024) throw new IOException("Legacy wallet exceeds size budget");
        NBTTagCompound root = CompressedStreamTools
            .func_152457_a(Files.readAllBytes(file), new NBTSizeTracker(32 * 1024 * 1024));
        NBTTagCompound tag = root.hasKey("data", 10) ? root.getCompoundTag("data") : root;
        if (!tag.hasKey("BalancesCents", 10) && !tag.hasKey("Balances", 10))
            throw new IOException("Unrecognized legacy wallet schema; refusing zero-balance migration");
        WalletSavedData wallet = new WalletSavedData(WalletSavedData.DATA_NAME);
        wallet.readFromNBT(tag);
        return wallet.snapshot();
    }

    private static void backupLegacy(Path world) throws IOException {
        Path target = world.resolve("nhkantan/legacy-backup");
        for (String name : new String[] { WalletSavedData.DATA_NAME, PositionSavedData.DATA_NAME }) {
            Path source = world.resolve("data/" + name + ".dat");
            if (Files.exists(source)) {
                Files.createDirectories(target);
                Path backup = target.resolve(name + ".dat");
                if (!Files.exists(backup)) Files.copy(source, backup);
            }
        }
    }

    /** Idempotent cleanup, only after the new checkpoint has been re-read and its migration marker checked. */
    public static void clearRecords(Path world, AtomicMarketStore committedStore) throws IOException {
        RealisticSavedData committed = committedStore.load();
        if (committed == null || !committed.migrated()) throw new IOException("No committed net-equity migration");
        backupLegacy(world);
        Files.deleteIfExists(world.resolve("data/" + WalletSavedData.DATA_NAME + ".dat"));
        Files.deleteIfExists(world.resolve("data/" + PositionSavedData.DATA_NAME + ".dat"));
        Path history = world.resolve("nhkantan/prices");
        if (Files.exists(history)) {
            Path backup = world.resolve("nhkantan/legacy-backup/prices");
            if (!Files.exists(backup)) {
                Files.createDirectories(backup.getParent());
                Files.move(history, backup);
                return;
            }
            List<Path> paths;
            try (Stream<Path> stream = Files.walk(history)) {
                paths = stream.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            }
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
}
