package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.yuelengm.nhkantan.market.server.AtomicMarketStore;
import com.yuelengm.nhkantan.market.server.LegacyMigration;
import com.yuelengm.nhkantan.market.server.RealisticSavedData;

public class AtomicMarketStoreTest {

    @Test
    public void cleanupRequiresCommitAndOnlyRemovesLegacyFinancialRecords() throws Exception {
        Path world = Files.createTempDirectory("nhkantan-migration-test-");
        Path data = Files.createDirectories(world.resolve("data"));
        Path history = Files.createDirectories(world.resolve("nhkantan/prices"));
        Path wallet = data.resolve("nhkantan_wallets.dat");
        Path positions = data.resolve("nhkantan_positions.dat");
        Path unrelated = data.resolve("other-mod.dat");
        Path price = history.resolve("stock.prices");
        Path checkpoint = data.resolve("realistic.dat");
        try {
            Files.write(wallet, new byte[] { 1 });
            Files.write(positions, new byte[] { 1 });
            Files.write(unrelated, new byte[] { 1 });
            Files.write(price, new byte[] { 1 });
            AtomicMarketStore store = new AtomicMarketStore(checkpoint);
            try {
                LegacyMigration.clearRecords(world, store);
                fail("Cleanup without migration commit must fail");
            } catch (java.io.IOException expected) {
                assertTrue(Files.exists(wallet));
                assertTrue(Files.exists(positions));
            }
            RealisticSavedData state = new RealisticSavedData("test");
            state.replace("committed", true);
            store.save(state);
            LegacyMigration.clearRecords(world, store);
            LegacyMigration.clearRecords(world, store);
            assertFalse(Files.exists(wallet));
            assertFalse(Files.exists(positions));
            assertFalse(Files.exists(history));
            assertTrue(Files.exists(unrelated));
        } finally {
            Files.deleteIfExists(wallet);
            Files.deleteIfExists(positions);
            Files.deleteIfExists(unrelated);
            Files.deleteIfExists(price);
            Files.deleteIfExists(history);
            Files.deleteIfExists(checkpoint);
            Files.deleteIfExists(data);
            Files.deleteIfExists(world.resolve("nhkantan"));
            Files.deleteIfExists(world);
        }
    }

    @Test
    public void compressedCheckpointCanBeReplacedAndReadBack() throws Exception {
        Path directory = Files.createTempDirectory("nhkantan-store-test-");
        Path file = directory.resolve("market.dat");
        try {
            AtomicMarketStore store = new AtomicMarketStore(file);
            assertNull(store.load());
            RealisticSavedData state = new RealisticSavedData("test");
            state.replace("first", true);
            store.save(state);
            state.replace("second", true);
            store.save(state);
            RealisticSavedData restored = store.load();
            assertEquals("second", restored.checkpoint());
            assertEquals(2, restored.revision());
            assertTrue(restored.migrated());
            assertFalse(state.isDirty());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
