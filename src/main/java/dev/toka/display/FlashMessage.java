package dev.toka.display;

/**
 * A message that is sent once, such as a broadcast. It shows under the other messages and goes away by itself.
 */
public final class FlashMessage extends DisplayMessage {
    FlashMessage(String id, String text, int priority, long expiresAt, BarEffect bar, long order) {
        super(id, text, priority, expiresAt, bar, order);
    }

    @Override
    public String kind() {
        return "flash";
    }
}
