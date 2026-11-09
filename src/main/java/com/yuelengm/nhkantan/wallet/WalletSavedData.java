package com.yuelengm.nhkantan.wallet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

/**
 * Balances of all players keyed by UUID, stored in the global map storage of the world so they do not depend on the
 * player being online. Balances are in cents (see {@link Money}) and may be negative (debt).
 */
public class WalletSavedData extends WorldSavedData {

    public static final String DATA_NAME = "nhkantan_wallets";

    private static final String NBT_BALANCES = "BalancesCents";
    /** Older saves stored whole units under this key; they are converted to cents when read. */
    private static final String NBT_LEGACY_BALANCES = "Balances";

    private final Map<UUID, Long> balances = new HashMap<>();

    /** Detached server-side balances for the one-time equity migration. Never send this map to clients. */
    public Map<UUID, Long> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(balances));
    }

    /** Constructor required by the map storage, which instantiates saved data reflectively. */
    public WalletSavedData(String name) {
        super(name);
    }

    public boolean has(UUID player) {
        return balances.containsKey(player);
    }

    public long get(UUID player) {
        Long balance = balances.get(player);
        return balance == null ? 0L : balance;
    }

    public void set(UUID player, long balance) {
        balances.put(player, balance);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        balances.clear();
        boolean legacy = !tag.hasKey(NBT_BALANCES);
        NBTTagCompound stored = tag.getCompoundTag(legacy ? NBT_LEGACY_BALANCES : NBT_BALANCES);
        for (String key : stored.func_150296_c()) {
            try {
                long value = stored.getLong(key);
                balances.put(UUID.fromString(key), legacy ? Money.cents(value) : value);
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; skip the corrupt entry
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagCompound stored = new NBTTagCompound();
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            stored.setLong(
                entry.getKey()
                    .toString(),
                entry.getValue());
        }
        tag.setTag(NBT_BALANCES, stored);
    }
}
