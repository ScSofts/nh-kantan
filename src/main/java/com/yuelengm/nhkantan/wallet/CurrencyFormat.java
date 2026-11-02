package com.yuelengm.nhkantan.wallet;

import java.math.BigDecimal;
import java.text.DecimalFormat;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.yuelengm.nhkantan.config.CurrencyConfig;

/**
 * Display text for market currency amounts given in cents. Uses the client language, so call it from GUI code.
 */
public class CurrencyFormat {

    // DecimalFormat is not thread safe; GUI code may format from several threads in single player
    private static final ThreadLocal<DecimalFormat> AMOUNT = ThreadLocal
        .withInitial(() -> new DecimalFormat("#,##0.##"));
    private static final ThreadLocal<DecimalFormat> BALANCE = ThreadLocal
        .withInitial(() -> new DecimalFormat("#,##0.00"));

    private CurrencyFormat() {}

    /** An amount with the currency name and only the decimals needed, e.g. {@code 1.1 NHC} or {@code 2 NHC}. */
    public static String amount(long cents) {
        return format(AMOUNT.get(), cents);
    }

    /** A balance always with two decimals, e.g. {@code 1,234.50 NHC}; red with a debt marker when negative. */
    public static String balance(long cents) {
        String text = format(BALANCE.get(), cents);
        if (cents >= 0) return text;
        return EnumChatFormatting.RED + text + " " + StatCollector.translateToLocal("gui.nhkantan.wallet.in_debt");
    }

    private static String format(DecimalFormat format, long cents) {
        return format.format(BigDecimal.valueOf(cents, 2)) + " " + CurrencyConfig.name;
    }
}
