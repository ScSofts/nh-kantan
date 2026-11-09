package com.yuelengm.nhkantan.market.server;

import java.nio.charset.StandardCharsets;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

/** Main-thread bridge to Minecraft persistence; the market worker exports a detached JSON checkpoint. */
public final class RealisticSavedData extends WorldSavedData {

    public static final String DATA_NAME = "nhkantan_realistic";
    private static final int VERSION = 1;
    private String checkpoint = "";
    private long revision;
    private boolean migrated;

    public RealisticSavedData(String name) {
        super(name);
    }

    public String checkpoint() {
        return checkpoint;
    }

    public long revision() {
        return revision;
    }

    public boolean migrated() {
        return migrated;
    }

    /** Called only after the worker's save barrier has completed. Never pass mutable worker objects here. */
    public void replace(String snapshot, boolean migrationComplete) {
        if (snapshot == null || snapshot.isEmpty()) throw new IllegalArgumentException("Empty market checkpoint");
        if (migrated && !migrationComplete) throw new IllegalArgumentException("Cannot undo an applied migration");
        long nextRevision = Math.addExact(revision, 1);
        checkpoint = snapshot;
        migrated = migrationComplete;
        revision = nextRevision;
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (tag.getInteger("Version") != VERSION || !tag.hasKey("Checkpoint") || tag.getLong("Revision") < 1) {
            throw new IllegalArgumentException(
                "Unsupported or incomplete realistic market save; preserve the source world");
        }
        String restored = new String(tag.getByteArray("Checkpoint"), StandardCharsets.UTF_8);
        if (restored.isEmpty()) throw new IllegalArgumentException("Market checkpoint is missing");
        checkpoint = restored;
        revision = tag.getLong("Revision");
        migrated = tag.getBoolean("Migrated");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        if (checkpoint.isEmpty()) throw new IllegalStateException("Refusing to write an uninitialized market save");
        tag.setInteger("Version", VERSION);
        tag.setLong("Revision", revision);
        tag.setBoolean("Migrated", migrated);
        // NBT strings use writeUTF and cannot hold a full book once it exceeds 65535 encoded bytes.
        tag.setByteArray("Checkpoint", checkpoint.getBytes(StandardCharsets.UTF_8));
    }
}
