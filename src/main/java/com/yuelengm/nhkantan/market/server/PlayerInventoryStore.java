package com.yuelengm.nhkantan.market.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.SaveHandler;

import cpw.mods.fml.common.FMLCommonHandler;

/** Main-thread-only inventory checkpoint utilities, including the integrated host's level.dat player copy. */
public final class PlayerInventoryStore {

    private PlayerInventoryStore() {}

    public static String inventory(EntityPlayerMP player) throws IOException {
        return inventory(player.inventory);
    }

    public static String inventory(InventoryPlayer inventory) throws IOException {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setTag("Inventory", inventory.writeToNBT(new NBTTagList()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(compound, output);
        return Base64.getEncoder()
            .encodeToString(output.toByteArray());
    }

    private static NBTTagCompound decode(String encoded) throws IOException {
        if (encoded == null || encoded.length() > 2 * 1024 * 1024)
            throw new IOException("Inventory escrow exceeds budget");
        return CompressedStreamTools.func_152457_a(
            Base64.getDecoder()
                .decode(encoded),
            new NBTSizeTracker(4 * 1024 * 1024));
    }

    public static boolean matches(EntityPlayerMP player, String expected) throws IOException {
        return player.inventory.writeToNBT(new NBTTagList())
            .equals(decode(expected).getTagList("Inventory", 10));
    }

    public static void apply(EntityPlayerMP player, String encoded) throws IOException {
        player.inventory.readFromNBT(decode(encoded).getTagList("Inventory", 10));
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
    }

    public static void save(MinecraftServer server, EntityPlayerMP player) throws IOException {
        if (player.getCommandSenderName()
            .equals(server.getServerOwner())
            && !(server.worldServerForDimension(0)
                .getSaveHandler() instanceof SaveHandler)) {
            throw new IOException("Unsupported integrated-player save handler");
        }
        // Keeps the integrated server's cached hostPlayerData current without referencing client-only classes.
        server.getConfigurationManager()
            .saveAllPlayerData();
        NBTTagCompound fullPlayer = new NBTTagCompound();
        player.writeToNBT(fullPlayer);
        Path world = server.worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory()
            .toPath();
        Path playerFile = world.resolve("playerdata")
            .resolve(player.getUniqueID() + ".dat");
        AtomicMarketStore.writeNbt(playerFile, fullPlayer);
        if (player.getCommandSenderName()
            .equals(server.getServerOwner())) {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag(
                "Data",
                server.worldServerForDimension(0)
                    .getWorldInfo()
                    .cloneNBTCompound(fullPlayer));
            FMLCommonHandler.instance()
                .handleWorldDataSave(
                    (SaveHandler) server.worldServerForDimension(0)
                        .getSaveHandler(),
                    server.worldServerForDimension(0)
                        .getWorldInfo(),
                    root);
            AtomicMarketStore.writeNbt(world.resolve("level.dat"), root);
        }
        NBTTagCompound verified = CompressedStreamTools
            .func_152457_a(Files.readAllBytes(playerFile), new NBTSizeTracker(16 * 1024 * 1024));
        if (!fullPlayer.getTagList("Inventory", 10)
            .equals(verified.getTagList("Inventory", 10))) {
            throw new IOException("Player inventory checkpoint verification failed");
        }
    }
}
