package com.yuelengm.nhkantan.market.order;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.yuelengm.nhkantan.market.server.RealisticSavedData;

public class RealisticSavedDataTest {

    @Test
    public void largeCheckpointAndMigrationMarkerRoundTrip() {
        String payload = new String(new char[100000]).replace('\0', 'x');
        RealisticSavedData saved = new RealisticSavedData("test");
        saved.replace(payload, true);
        NBTTagCompound tag = new NBTTagCompound();
        saved.writeToNBT(tag);
        assertEquals(100000, tag.getByteArray("Checkpoint").length);
        RealisticSavedData copy = new RealisticSavedData("test");
        copy.readFromNBT(tag);
        assertEquals(payload, copy.checkpoint());
        assertTrue(copy.migrated());
        assertEquals(1, copy.revision());
        try {
            copy.replace("replacement", false);
            fail("Migration marker cannot be undone");
        } catch (IllegalArgumentException expected) {
            assertEquals(payload, copy.checkpoint());
            assertEquals(1, copy.revision());
        }
    }
}
