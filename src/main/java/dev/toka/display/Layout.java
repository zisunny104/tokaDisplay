package dev.toka.display;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one player sees in the boss bar area: the base information on the first line that nobody else can
 * change, then the pinned messages, then the messages that are sent once. The messages may also change how full
 * the bar is and its color. It does not know the game, so it can be tested alone.
 */
public final class Layout {
    public static final long SECOND = 1_000_000_000L;

    /**
     * @param flashPrefix  put before the first line of every message that is sent once, such as "§f- "
     * @param pinnedPrefix put before the first line of every pinned message, such as "§f= "
     * @param trimBlank    true to drop the lines that have nothing to read and the spaces at the end of a line
     * @param barGapLines  lines kept empty under the first line for the bar, only when something follows
     * @param minWidth     the least width of the text in the width of a space, the first line is centered in it
     */
    public record Settings(int flashSeconds, int maxFlash, int maxPinned, int maxText, BarColor defaultColor,
                           long maxPinnedLifetime, String flashPrefix, String pinnedPrefix, boolean trimBlank, int barGapLines, int minWidth) {
        public Settings(int flashSeconds, int maxFlash, int maxPinned, int maxText, BarColor defaultColor, long maxPinnedLifetime) {
            this(flashSeconds, maxFlash, maxPinned, maxText, defaultColor, maxPinnedLifetime, "", "", false, 0, 0);
        }
    }

    /**
     * @param text the lines under each other
     * @param bar  how full the bar is and its color
     */
    public record Frame(String text, BarEffect bar) {
    }

    private final Settings settings;
    private String baseText = "";
    private BarEffect baseBar;
    private long baseExpiresAt;
    private final Map<String, PinnedMessage> pinned = new LinkedHashMap<>();
    private final ArrayDeque<FlashMessage> flashes = new ArrayDeque<>();
    private long counter;
    /** The last frame, kept until a message changes or the first message runs out. */
    private Frame cached;
    private long cachedUntil;

    public Layout(Settings settings) {
        this.settings = Objects.requireNonNull(settings);
        this.baseBar = new BarEffect(100, settings.defaultColor());
    }

    private void checkText(String text) {
        if (text == null || text.length() > this.settings.maxText()) {
            throw new IllegalArgumentException("Text is missing or too long");
        }
    }

    private static long expiry(long now, long lifetime) {
        return lifetime <= 0 ? 0 : now + lifetime;
    }

    /** A pinned message that was given no time stays at most as long as the config allows, 0 for no limit. */
    private long pinnedLifetime(long lifetime) {
        return lifetime > 0 ? lifetime : this.settings.maxPinnedLifetime();
    }

    /**
     * Sets the base information. Only the owner of the base calls this, the messages cannot change it.
     *
     * @param lifetime nanoseconds the base stays when it is not sent again, it must be more than 0
     */
    public void base(String text, BarEffect bar, long lifetime, long now) {
        this.checkText(text);
        if (lifetime <= 0) {
            throw new IllegalArgumentException("The base needs a lifetime");
        }
        this.baseText = text;
        this.baseBar = bar != null ? bar : new BarEffect(100, this.settings.defaultColor());
        this.baseExpiresAt = now + lifetime;
        this.cached = null;
    }

    /**
     * Pins a message. A message with the same id is replaced and keeps its place.
     *
     * @param lifetime nanoseconds the message stays, 0 when it stays until it is removed
     * @param bar      what the message does to the bar, null for nothing
     */
    public void pin(String id, String text, int priority, BarEffect bar, long lifetime, long now) {
        this.checkText(text);
        Objects.requireNonNull(id);
        PinnedMessage old = this.pinned.get(id);
        if (old == null && this.pinned.size() >= this.settings.maxPinned()) {
            throw new IllegalStateException("Too many pinned messages");
        }
        long order = old != null ? old.order() : ++this.counter;
        this.cached = null;
        this.pinned.put(id, new PinnedMessage(id, text, priority, expiry(now, this.pinnedLifetime(lifetime)), bar, order));
    }

    /**
     * @return true when there was a pinned message with the id
     */
    public boolean unpin(String id) {
        this.cached = null;
        return this.pinned.remove(id) != null;
    }

    /**
     * Sends a message once. The oldest one goes first when there are more than the area has room for.
     *
     * @param lifetime nanoseconds the message stays, 0 for the default
     */
    public void flash(String text, BarEffect bar, long lifetime, long now) {
        this.checkText(text);
        long stay = lifetime > 0 ? lifetime : this.settings.flashSeconds() * SECOND;
        this.cached = null;
        this.flashes.addLast(new FlashMessage("flash-" + (this.counter + 1), text, 0, now + stay, bar, ++this.counter));
        while (this.flashes.size() > this.settings.maxFlash()) {
            this.flashes.removeFirst();
        }
    }

    /**
     * One message as the status command shows it.
     *
     * @param position the number of the message in the list, from 1, as it is shown to the player
     * @param seconds  seconds left, -1 when it stays until it is removed
     */
    public record Entry(int position, String kind, String id, int priority, long seconds, String text) {
    }

    private List<DisplayMessage> shown(long now) {
        this.prune(now);
        List<PinnedMessage> ordered = new ArrayList<>(this.pinned.values());
        ordered.sort(Comparator.comparingInt(PinnedMessage::priority).reversed().thenComparingLong(PinnedMessage::order));
        List<DisplayMessage> all = new ArrayList<>(ordered);
        all.addAll(this.flashes);
        return all;
    }

    /**
     * @return the pinned messages and the messages sent once in the order they are shown
     */
    public List<Entry> entries(long now) {
        List<Entry> entries = new ArrayList<>();
        for (DisplayMessage message : this.shown(now)) {
            long left = message.expiresAt() == 0 ? -1 : Math.max(0, (message.expiresAt() - now + SECOND - 1) / SECOND);
            entries.add(new Entry(entries.size() + 1, message.kind(), message.id(), message.priority(), left, message.text()));
        }
        return entries;
    }

    /**
     * Removes a message by the number it has in the list or by its id.
     *
     * @return true when a message was removed
     */
    public boolean remove(String idOrNumber, long now) {
        List<DisplayMessage> all = this.shown(now);
        DisplayMessage target = null;
        if (idOrNumber.matches("[0-9]{1,4}")) {
            int number = Integer.parseInt(idOrNumber);
            if (number >= 1 && number <= all.size()) {
                target = all.get(number - 1);
            }
        }
        if (target == null) {
            for (DisplayMessage message : all) {
                if (message.id().equals(idOrNumber)) {
                    target = message;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        this.cached = null;
        if (target instanceof PinnedMessage) {
            return this.pinned.remove(target.id()) != null;
        }
        return this.flashes.remove(target);
    }

    /**
     * Removes every pinned message and every message sent once. The base information stays.
     */
    public void clearMessages() {
        this.cached = null;
        this.pinned.clear();
        this.flashes.clear();
    }

    private void addLines(List<String> lines, String text, String prefix) {
        String clean = this.settings.trimBlank() ? trimBlank(text) : text;
        if (!clean.isEmpty()) {
            lines.add(prefix + clean);
        }
    }

    /**
     * Removes the spaces at the end of each line and the lines with nothing to read, which are only spaces and color codes.
     */
    static String trimBlank(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String visible = line.replaceAll("§.", "").strip();
            if (visible.isEmpty()) {
                continue;
            }
            kept.add(line.stripTrailing());
        }
        return String.join("\n", kept);
    }

    /**
     * An estimate of the width of a line in the width of a space: letters and digits are one, narrow marks such as the
     * colon and the letter i are a half, Chinese characters and other wide ones are two, color codes are nothing.
     */
    static double width(String line) {
        double width = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (c < 128) {
                width += ".,:;'|!iIl`".indexOf(c) >= 0 ? 0.5 : 1;
            } else {
                width += 2;
            }
        }
        return width;
    }

    /**
     * The longest text the client accepts in a boss bar. The client leaves the game with a bad packet error from 412
     * bytes (258 characters) on and takes 411 bytes (246 characters), so both stay a little under that.
     */
    static final int MAX_BYTES = 380;
    static final int MAX_CHARS = 240;

    static boolean tooLong(String text) {
        return text.length() > MAX_CHARS || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /**
     * Cuts a text that is too long, never inside a color code, and marks the cut with an ellipsis.
     */
    static String limit(String text) {
        if (!tooLong(text)) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            // room is kept for the ellipsis, three bytes and one character
            if (bytes + size > MAX_BYTES - 3 || kept.length() + Character.charCount(codePoint) > MAX_CHARS - 1) {
                break;
            }
            kept.appendCodePoint(codePoint);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        if (kept.length() > 0 && kept.charAt(kept.length() - 1) == '§') {
            kept.setLength(kept.length() - 1);
        }
        return kept.append('…').toString();
    }

    private void prune(long now) {
        this.pinned.values().removeIf(message -> message.expired(now));
        this.flashes.removeIf(message -> message.expired(now));
    }

    private boolean baseAlive(long now) {
        return this.baseExpiresAt > now && !this.baseText.isEmpty();
    }

    public boolean isEmpty(long now) {
        this.prune(now);
        return !this.baseAlive(now) && this.pinned.isEmpty() && this.flashes.isEmpty();
    }

    /**
     * @return what to show now: the base, then the pinned messages by priority, then the messages sent once
     */
    public Frame frame(long now) {
        if (this.cached != null && now < this.cachedUntil) {
            return this.cached;
        }
        this.prune(now);
        // The first line of the base information, and everything under it
        String first = "";
        List<String> after = new ArrayList<>();
        if (this.baseAlive(now)) {
            String clean = this.settings.trimBlank() ? trimBlank(this.baseText) : this.baseText;
            int newline = clean.indexOf('\n');
            first = newline < 0 ? clean : clean.substring(0, newline);
            if (newline >= 0 && !clean.substring(newline + 1).isEmpty()) {
                after.add(clean.substring(newline + 1));
            }
        }

        List<PinnedMessage> ordered = new ArrayList<>(this.pinned.values());
        ordered.sort(Comparator.comparingInt(PinnedMessage::priority).reversed().thenComparingLong(PinnedMessage::order));
        for (PinnedMessage message : ordered) {
            this.addLines(after, message.text(), this.settings.pinnedPrefix());
        }
        for (FlashMessage message : this.flashes) {
            this.addLines(after, message.text(), this.settings.flashPrefix());
        }

        List<String> lines = new ArrayList<>();
        int gapLines = 0;
        if (!first.isEmpty()) {
            if (this.settings.trimBlank() && this.settings.barGapLines() > 0 && !after.isEmpty()) {
                // The other lines stay on the left; the first line is moved to the middle of the text with spaces,
                // and the empty line for the bar is as wide as the text, so that the text does not change its width
                double widest = Math.max(this.settings.minWidth(), width(first));
                for (String line : after) {
                    for (String part : line.split("\n", -1)) {
                        widest = Math.max(widest, width(part));
                    }
                }
                int total = (int) Math.ceil(widest);
                int pad = (int) Math.round((total - width(first)) / 2.0);
                lines.add(" ".repeat(Math.max(0, pad)) + first);
                for (int i = 0; i < this.settings.barGapLines(); i++) {
                    lines.add(" ".repeat(total));
                    gapLines++;
                }
            } else {
                lines.add(first);
            }
        }
        lines.addAll(after);

        DisplayMessage chosen = null;
        List<DisplayMessage> all = new ArrayList<>(ordered);
        all.addAll(this.flashes);
        for (DisplayMessage message : all) {
            if (message.bar() == null) {
                continue;
            }
            if (chosen == null || message.priority() > chosen.priority()
                    || (message.priority() == chosen.priority() && message.order() > chosen.order())) {
                chosen = message;
            }
        }

        BarEffect bar = chosen != null ? chosen.bar() : this.baseBar;
        long until = this.baseAlive(now) ? this.baseExpiresAt : Long.MAX_VALUE;
        for (DisplayMessage message : all) {
            if (message.expiresAt() != 0) {
                until = Math.min(until, message.expiresAt());
            }
        }
        String text = String.join("\n", lines);
        if (tooLong(text) && gapLines > 0) {
            // The empty lines for the bar only keep the text as wide as its widest line, a line of one space is enough
            for (int i = 1; i <= gapLines; i++) {
                lines.set(lines.size() - after.size() - i, " ");
            }
            text = String.join("\n", lines);
        }
        this.cached = new Frame(limit(text), bar);
        this.cachedUntil = until;
        return this.cached;
    }
}
