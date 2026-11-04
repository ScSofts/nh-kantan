package com.yuelengm.nhkantan.trading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Text form of a player's open positions for GUI sync: one line per position, tab-separated fields. Stock ids are
 * validated to contain neither tabs nor line breaks, so no escaping is needed.
 */
public class PositionCodec {

    private static final int FIELDS = 8;

    private PositionCodec() {}

    public static String encode(List<Position> positions) {
        StringBuilder text = new StringBuilder();
        for (Position position : positions) {
            if (text.length() > 0) text.append('\n');
            text.append(position.id())
                .append('\t')
                .append(position.stockId())
                .append('\t')
                .append(
                    position.direction()
                        .name())
                .append('\t')
                .append(position.marginCents())
                .append('\t')
                .append(position.leverage())
                .append('\t')
                .append(position.openPrice())
                .append('\t')
                .append(position.openFeeCents())
                .append('\t')
                .append(position.liquidationPercent());
        }
        return text.toString();
    }

    /** Parses {@link #encode} output; malformed lines are skipped. */
    public static List<Position> decode(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<Position> positions = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != FIELDS) continue;
            try {
                positions.add(
                    new Position(
                        Long.parseLong(fields[0]),
                        fields[1],
                        Direction.valueOf(fields[2]),
                        Long.parseLong(fields[3]),
                        Integer.parseInt(fields[4]),
                        Double.parseDouble(fields[5]),
                        Long.parseLong(fields[6]),
                        Double.parseDouble(fields[7])));
            } catch (IllegalArgumentException ignored) {
                // Malformed number or direction; skip the line
            }
        }
        return positions;
    }
}
