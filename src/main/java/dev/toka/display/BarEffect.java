package dev.toka.display;

import java.util.Objects;

/**
 * What a message does to the boss bar: how full it is (0 to 100) and its color.
 */
public record BarEffect(float fill, BarColor color) {
    public BarEffect {
        if (!Float.isFinite(fill)) {
            throw new IllegalArgumentException("The fill of the bar must be a number");
        }
        fill = Math.max(0, Math.min(100, fill));
        Objects.requireNonNull(color);
    }
}
