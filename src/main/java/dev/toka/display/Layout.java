package dev.toka.display;

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

    public record Settings(int flashSeconds, int maxFlash, int maxPinned, int maxText, BarColor defaultColor) {
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
        this.pinned.put(id, new PinnedMessage(id, text, priority, expiry(now, lifetime), bar, order));
    }

    /**
     * @return true when there was a pinned message with the id
     */
    public boolean unpin(String id) {
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
        this.flashes.addLast(new FlashMessage("flash-" + (this.counter + 1), text, 0, now + stay, bar, ++this.counter));
        while (this.flashes.size() > this.settings.maxFlash()) {
            this.flashes.removeFirst();
        }
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
        this.prune(now);
        List<String> lines = new ArrayList<>();
        if (this.baseAlive(now)) {
            lines.add(this.baseText);
        }

        List<PinnedMessage> ordered = new ArrayList<>(this.pinned.values());
        ordered.sort(Comparator.comparingInt(PinnedMessage::priority).reversed().thenComparingLong(PinnedMessage::order));
        for (PinnedMessage message : ordered) {
            lines.add(message.text());
        }
        for (FlashMessage message : this.flashes) {
            lines.add(message.text());
        }

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
        return new Frame(String.join("\n", lines), bar);
    }
}
