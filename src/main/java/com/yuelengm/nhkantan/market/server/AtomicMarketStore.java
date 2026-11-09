package com.yuelengm.nhkantan.market.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;

/** Atomic world-local NBT checkpoint store. Unlike MapStorage, write failures propagate to the caller. */
public final class AtomicMarketStore {

    private static final long MAX_BYTES = 32 * 1024 * 1024;
    private final Path file;

    public AtomicMarketStore(Path file) {
        this.file = file;
    }

    public RealisticSavedData load() throws IOException {
        if (!Files.exists(file)) return null;
        if (Files.size(file) > MAX_BYTES) throw new IOException("Market checkpoint exceeds size budget");
        NBTTagCompound tag = CompressedStreamTools
            .func_152457_a(Files.readAllBytes(file), new NBTSizeTracker(MAX_BYTES));
        RealisticSavedData data = new RealisticSavedData(RealisticSavedData.DATA_NAME);
        data.readFromNBT(tag);
        return data;
    }

    /** Call on the main thread with the immutable result of a completed market save barrier. */
    public void save(RealisticSavedData data) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        if (tag.getByteArray("Checkpoint").length > MAX_BYTES / 2) {
            throw new IOException("Market state exceeds size budget");
        }
        writeNbt(file, tag);
        data.setDirty(false);
    }

    public static void writeNbt(Path file, NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, buffer);
        byte[] encoded = buffer.toByteArray();
        Path parent = file.toAbsolutePath()
            .getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "nhkantan-", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // No non-atomic fallback: keep the previous valid checkpoint if this filesystem cannot replace safely.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
