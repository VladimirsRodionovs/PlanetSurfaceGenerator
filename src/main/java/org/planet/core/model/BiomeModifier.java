package org.planet.core.model;

public enum BiomeModifier {
    HIGH_SEASONALITY(1 << 0),
    COLD_WINTER(1 << 1),
    SEVERE_WINTER(1 << 2),
    MONSOONAL(1 << 3),
    WET_SUMMER_DRY_WINTER(1 << 4),
    DRY_SUMMER_WET_WINTER(1 << 5),
    FROST_RISK(1 << 6),
    HEAT_STRESS(1 << 7),
    RIVER_FED(1 << 8),
    COASTAL_BUFFERED(1 << 9);

    public final int bit;

    BiomeModifier(int bit) {
        this.bit = bit;
    }

    public static int add(int mask, BiomeModifier mod) {
        return mask | mod.bit;
    }

    public static boolean has(int mask, BiomeModifier mod) {
        return (mask & mod.bit) != 0;
    }

    public static String toCsv(int mask) {
        if (mask == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (BiomeModifier mod : values()) {
            if ((mask & mod.bit) == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(mod.name());
        }
        return sb.toString();
    }
}
