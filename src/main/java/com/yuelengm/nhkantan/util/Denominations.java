package com.yuelengm.nhkantan.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;

/**
 * A family of item types that each stand for a whole number of base units, like coins of several sizes. A single item
 * type is a family with one denomination worth 1.
 */
public class Denominations {

    private final List<Denomination> largestFirst;
    private final ItemSpec base;

    /** One item type of a family and how many base units it is worth. */
    public static class Denomination {

        private final ItemSpec item;
        private final int units;

        public Denomination(ItemSpec item, int units) {
            if (units <= 0) throw new IllegalArgumentException("units must be positive");
            this.item = item;
            this.units = units;
        }

        public ItemSpec item() {
            return item;
        }

        public int units() {
            return units;
        }
    }

    /**
     * @param denominations at least one entry, one of which must be worth exactly 1 unit
     */
    public Denominations(List<Denomination> denominations) {
        List<Denomination> sorted = new ArrayList<>(denominations);
        sorted.sort(
            Comparator.comparingInt(Denomination::units)
                .reversed());
        Denomination smallest = sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
        if (smallest == null || smallest.units() != 1) {
            throw new IllegalArgumentException("a denomination worth 1 unit is required");
        }
        this.largestFirst = Collections.unmodifiableList(sorted);
        this.base = smallest.item();
    }

    public static Denominations single(ItemSpec item) {
        return new Denominations(Collections.singletonList(new Denomination(item, 1)));
    }

    /** The item worth one unit, used to represent the whole family. */
    public ItemSpec base() {
        return base;
    }

    /** Units one item of the stack is worth, or 0 if the stack is not part of this family. */
    public int unitsPer(ItemStack stack) {
        for (Denomination denomination : largestFirst) {
            if (denomination.item()
                .matches(stack)) return denomination.units();
        }
        return 0;
    }

    /** Units the whole stack is worth, or 0 if the stack is not part of this family. */
    public long unitsOf(ItemStack stack) {
        return (long) unitsPer(stack) * stack.stackSize;
    }

    /** Splits {@code units} into the fewest items, largest denominations first; item types not needed are omitted. */
    public Map<ItemSpec, Integer> split(int units) {
        Map<ItemSpec, Integer> items = new LinkedHashMap<>();
        int remaining = units;
        for (Denomination denomination : largestFirst) {
            int count = remaining / denomination.units();
            if (count == 0) continue;
            items.put(denomination.item(), count);
            remaining -= count * denomination.units();
        }
        return items;
    }
}
