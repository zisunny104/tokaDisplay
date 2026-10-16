package dev.toka.display;

import java.util.Locale;
import java.util.Map;

/** The colors the game has for a boss bar. The game does not allow any other color for the bar itself. */
public enum BarColor {
    PINK(0), BLUE(1), RED(2), GREEN(3), YELLOW(4), PURPLE(5), WHITE(7), REBECCA_PURPLE(6);

    private final int clientIndex;

    BarColor(int clientIndex) {
        this.clientIndex = clientIndex;
    }

    /**
     * @return the number the game client uses for the color. The core has no dark purple, so its number for white is the one
     *         of the dark purple.
     */
    public int clientIndex() {
        return this.clientIndex;
    }

    /**
     * @param name     a color name, or an alias from the config; blank gives the fallback
     * @param aliases  alias to color name, from the config
     * @param fallback the color when the name is blank
     * @throws IllegalArgumentException when the name is neither a color nor an alias
     */
    public static BarColor parse(String name, Map<String, String> aliases, BarColor fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim();
        String target = aliases.getOrDefault(key, aliases.getOrDefault(key.toLowerCase(Locale.ROOT), key));
        try {
            return valueOf(target.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown bar color: " + name);
        }
    }
}
