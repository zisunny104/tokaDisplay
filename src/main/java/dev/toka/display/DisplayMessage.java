package dev.toka.display;

import java.util.Objects;

/**
 * A line of text in the message area under the boss bar. A message may also change the bar
 * (how full it is and its color). The kinds of messages only differ in how long they stay and
 * whether they can be replaced, everything else is shared here.
 */
public abstract class DisplayMessage {
    private final String id;
    private final String text;
    private final int priority;
    private final long expiresAt;
    private final BarEffect bar;
    private final long order;

    /**
     * @param expiresAt monotonic time in nanoseconds when the message goes away, 0 when it stays until it is removed
     * @param bar       what the message does to the bar, null when it leaves the bar alone
     * @param order     the sequence number, so that messages of the same priority keep the order they were sent in
     */
    protected DisplayMessage(String id, String text, int priority, long expiresAt, BarEffect bar, long order) {
        this.id = Objects.requireNonNull(id);
        this.text = Objects.requireNonNull(text);
        this.priority = priority;
        this.expiresAt = expiresAt;
        this.bar = bar;
        this.order = order;
    }

    /**
     * @return a short name of the kind, for the status command
     */
    public abstract String kind();

    public boolean expired(long now) {
        return this.expiresAt != 0 && this.expiresAt <= now;
    }

    public String id() {
        return this.id;
    }

    public String text() {
        return this.text;
    }

    public int priority() {
        return this.priority;
    }

    public BarEffect bar() {
        return this.bar;
    }

    public long order() {
        return this.order;
    }

    public long expiresAt() {
        return this.expiresAt;
    }
}
