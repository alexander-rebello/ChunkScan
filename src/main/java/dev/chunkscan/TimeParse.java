package dev.chunkscan;

import java.util.Locale;

/** Parses durations like 30s, 5m, 1h, 600t (ticks); a plain number means seconds. */
public final class TimeParse {

    private TimeParse() {}

    public static long toTicks(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new NumberFormatException("empty");
        char last = s.charAt(s.length() - 1);
        long mult;
        switch (last) {
            case 't': mult = 1; break;
            case 's': mult = 20; break;
            case 'm': mult = 20 * 60; break;
            case 'h': mult = 20 * 3600; break;
            default:
                if (!Character.isDigit(last)) throw new NumberFormatException(s);
                mult = 20;
        }
        if (!Character.isDigit(last)) s = s.substring(0, s.length() - 1);
        long v = Long.parseLong(s);
        if (v < 0) throw new NumberFormatException(s);
        return v * mult;
    }

    public static String format(long ticks) {
        if (ticks % 20 != 0) return ticks + "t";
        long s = ticks / 20;
        if (s >= 3600 && s % 3600 == 0) return (s / 3600) + "h";
        if (s >= 60 && s % 60 == 0) return (s / 60) + "m";
        return s + "s";
    }
}
