package dev.toka.display;

import java.util.Objects;
import java.util.function.Function;

/**
 * Builds a player's name tag from a format such as {@code ${prefix}§r${affiliation}§r${mode}[${title}${mode}${name}${mode}]}.
 * Each {@code ${id}} is replaced by what the part with that id gives, or by nothing when no plugin provides it, so
 * the layout is set in config.yml and no plugin's data is written into this one.
 */
final class NameTag {
    private final String format;

    NameTag(String format) {
        this.format = Objects.requireNonNull(format);
    }

    /** @param value gives the text for an id, or null for nothing */
    String compose(Function<String, String> value) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < format.length()) {
            int open = format.indexOf("${", at);
            int close = open < 0 ? -1 : format.indexOf('}', open + 2);
            if (open < 0 || close < 0) { out.append(format, at, format.length()); break; }
            out.append(format, at, open);
            String text = value.apply(format.substring(open + 2, close));
            if (text != null) out.append(text);
            at = close + 1;
        }
        return out.toString();
    }
}
