package dev.toka.display;

/**
 * A message that stays where it is until it is removed or until the time it was given. Sending it again with the
 * same id changes its text and keeps its place.
 */
public final class PinnedMessage extends DisplayMessage {
    PinnedMessage(String id, String text, int priority, long expiresAt, BarEffect bar, long order) {
        super(id, text, priority, expiresAt, bar, order);
    }

    @Override
    public String kind() {
        return "pinned";
    }
}
