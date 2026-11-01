package com.yuelengm.nhkantan.wallet;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.yuelengm.nhkantan.config.CurrencyConfig;

/**
 * Display text for market currency amounts. Uses the client language, so call it from GUI code.
 */
public class CurrencyFormat {

    private CurrencyFormat() {}

    /** Amount with thousands separators and the currency name, e.g. {@code 6,000 市场币}. */
    public static String amount(long amount) {
        return String.format("%,d %s", amount, CurrencyConfig.name);
    }

    /** Balance as {@link #amount}; red with a debt marker when negative. */
    public static String balance(long balance) {
        String text = amount(balance);
        if (balance >= 0) return text;
        return EnumChatFormatting.RED + text + " " + StatCollector.translateToLocal("gui.nhkantan.wallet.in_debt");
    }
}
