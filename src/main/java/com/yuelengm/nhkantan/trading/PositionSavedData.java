package com.yuelengm.nhkantan.trading;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

/**
 * Open margin positions of all players keyed by UUID, stored in the global map storage of the world so they do not
 * depend on the player being online. Restarting the server never resets a position.
 */
public class PositionSavedData extends WorldSavedData {

    public static final String DATA_NAME = "nhkantan_positions";

    private static final String NBT_PLAYERS = "Players";
    private static final String NBT_POSITIONS = "Positions";
    private static final String NBT_NEXT_ID = "NextId";
    private static final long FIRST_ID = 1L;

    private static final String NBT_ID = "Id";
    private static final String NBT_STOCK = "Stock";
    private static final String NBT_DIRECTION = "Direction";
    private static final String NBT_MARGIN = "MarginCents";
    private static final String NBT_LEVERAGE = "Leverage";
    private static final String NBT_OPEN_PRICE = "OpenPrice";
    private static final String NBT_OPEN_FEE = "OpenFeeCents";
    private static final String NBT_LIQUIDATION = "LiquidationPercent";

    private final Map<UUID, Map<Long, Position>> positions = new HashMap<>();
    private long nextId = FIRST_ID;

    /** Constructor required by the map storage, which instantiates saved data reflectively. */
    public PositionSavedData(String name) {
        super(name);
    }

    /** Next position id, unique across restarts. */
    public synchronized long claimId() {
        long id = nextId;
        if (nextId < Long.MAX_VALUE) nextId++;
        markDirty();
        return id;
    }

    public synchronized void add(UUID player, Position position) {
        positionsOf(player).put(position.id(), position);
        markDirty();
    }

    /** Removes and returns the position, or {@code null} when the player has no such position. */
    @Nullable
    public synchronized Position remove(UUID player, long positionId) {
        Map<Long, Position> owned = positions.get(player);
        if (owned == null) return null;
        Position removed = owned.remove(positionId);
        if (removed == null) return null;
        if (owned.isEmpty()) positions.remove(player);
        markDirty();
        return removed;
    }

    /** All open positions of the player, in id order. */
    public synchronized List<Position> owned(UUID player) {
        Map<Long, Position> owned = positions.get(player);
        if (owned == null || owned.isEmpty()) return Collections.emptyList();
        List<Position> result = new ArrayList<>(owned.values());
        result.sort(Comparator.comparingLong(Position::id));
        return result;
    }

    /** Every owner with their open positions, in id order; the lists are copies. */
    public synchronized Map<UUID, List<Position>> snapshot() {
        Map<UUID, List<Position>> result = new HashMap<>();
        for (UUID player : positions.keySet()) result.put(player, owned(player));
        return result;
    }

    private Map<Long, Position> positionsOf(UUID player) {
        return positions.computeIfAbsent(player, key -> new HashMap<>());
    }

    @Override
    public synchronized void readFromNBT(NBTTagCompound tag) {
        positions.clear();
        nextId = tag.getLong(NBT_NEXT_ID);
        if (nextId < FIRST_ID) nextId = FIRST_ID;
        NBTTagList players = tag.getTagList(NBT_PLAYERS, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound playerTag = players.getCompoundTagAt(i);
            UUID player;
            try {
                player = UUID.fromString(playerTag.getString(NBT_ID));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; skip the corrupt entry
                continue;
            }
            Map<Long, Position> owned = positionsOf(player);
            NBTTagList stored = playerTag.getTagList(NBT_POSITIONS, Constants.NBT.TAG_COMPOUND);
            for (int j = 0; j < stored.tagCount(); j++) {
                Position position = readPosition(stored.getCompoundTagAt(j));
                if (position != null) owned.put(position.id(), position);
            }
            if (owned.isEmpty()) positions.remove(player);
        }
    }

    @Override
    public synchronized void writeToNBT(NBTTagCompound tag) {
        tag.setLong(NBT_NEXT_ID, nextId);
        NBTTagList players = new NBTTagList();
        for (Map.Entry<UUID, Map<Long, Position>> entry : positions.entrySet()) {
            Collection<Position> owned = entry.getValue()
                .values();
            if (owned.isEmpty()) continue;
            NBTTagCompound playerTag = new NBTTagCompound();
            playerTag.setString(
                NBT_ID,
                entry.getKey()
                    .toString());
            NBTTagList stored = new NBTTagList();
            for (Position position : owned) stored.appendTag(writePosition(position));
            playerTag.setTag(NBT_POSITIONS, stored);
            players.appendTag(playerTag);
        }
        tag.setTag(NBT_PLAYERS, players);
    }

    private static Position readPosition(NBTTagCompound tag) {
        long id = tag.getLong(NBT_ID);
        String stockId = tag.getString(NBT_STOCK);
        Direction direction;
        try {
            direction = Direction.valueOf(tag.getString(NBT_DIRECTION));
        } catch (IllegalArgumentException e) {
            return null;
        }
        long marginCents = tag.getLong(NBT_MARGIN);
        int leverage = tag.getInteger(NBT_LEVERAGE);
        double openPrice = tag.getDouble(NBT_OPEN_PRICE);
        long openFeeCents = tag.getLong(NBT_OPEN_FEE);
        double liquidationPercent = tag.getDouble(NBT_LIQUIDATION);
        if (id < FIRST_ID || stockId.isEmpty()
            || marginCents <= 0L
            || leverage <= 0
            || openPrice <= 0.0D
            || openFeeCents < 0L
            || liquidationPercent < 0.0D) return null;
        return new Position(id, stockId, direction, marginCents, leverage, openPrice, openFeeCents, liquidationPercent);
    }

    private static NBTTagCompound writePosition(Position position) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong(NBT_ID, position.id());
        tag.setString(NBT_STOCK, position.stockId());
        tag.setString(
            NBT_DIRECTION,
            position.direction()
                .name());
        tag.setLong(NBT_MARGIN, position.marginCents());
        tag.setInteger(NBT_LEVERAGE, position.leverage());
        tag.setDouble(NBT_OPEN_PRICE, position.openPrice());
        tag.setLong(NBT_OPEN_FEE, position.openFeeCents());
        tag.setDouble(NBT_LIQUIDATION, position.liquidationPercent());
        return tag;
    }
}
