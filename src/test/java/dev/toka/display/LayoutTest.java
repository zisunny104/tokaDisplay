package dev.toka.display;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LayoutTest {
    private static final long S = Layout.SECOND;

    private static Layout layout() {
        return new Layout(new Layout.Settings(15, 3, 2, 100, BarColor.WHITE));
    }

    private static BarEffect bar(float fill, BarColor color) {
        return new BarEffect(fill, color);
    }

    @Test
    public void baseFirstThenPinnedThenFlash() {
        Layout layout = layout();
        layout.flash("notice", null, 0, 0);
        layout.pin("cooldown", "cooldown", 0, null, 0, 0);
        layout.base("info", null, 10 * S, 0);
        assertEquals("info\ncooldown\nnotice", layout.frame(1).text());
    }

    @Test
    public void pinnedMessagesFollowPriorityThenTheOrderTheyWereSentIn() {
        Layout layout = layout();
        layout.pin("a", "a", 0, null, 0, 0);
        layout.pin("b", "b", 5, null, 0, 0);
        assertEquals("b\na", layout.frame(1).text());
    }

    @Test
    public void sendingAPinnedMessageAgainChangesTheTextAndKeepsThePlace() {
        Layout layout = layout();
        layout.pin("a", "a1", 0, null, 0, 0);
        layout.pin("b", "b", 0, null, 0, 0);
        layout.pin("a", "a2", 0, null, 0, 1);
        assertEquals("a2\nb", layout.frame(2).text());
    }

    @Test
    public void nothingCanChangeTheBase() {
        Layout layout = layout();
        layout.base("info", bar(100, BarColor.WHITE), 10 * S, 0);
        layout.pin("base", "other", 100, null, 0, 0);
        layout.flash("other", null, 0, 0);
        assertTrue(layout.frame(1).text().startsWith("info\n"));
    }

    @Test
    public void flashMessageGoesAwayAfterTheDefaultAndAfterAGivenTime() {
        Layout layout = layout();
        layout.flash("default", null, 0, 0);
        layout.flash("short", null, 2 * S, 0);
        assertEquals("default\nshort", layout.frame(1 * S).text());
        assertEquals("default", layout.frame(2 * S).text());
        assertTrue(layout.isEmpty(15 * S));
    }

    @Test
    public void pinnedMessageStaysUntilItsTimeOrItIsRemoved() {
        Layout layout = layout();
        layout.pin("stay", "stay", 0, null, 0, 0);
        layout.pin("timed", "timed", 0, null, 5 * S, 0);
        assertEquals("stay\ntimed", layout.frame(4 * S).text());
        assertEquals("stay", layout.frame(5 * S).text());
        assertEquals("stay", layout.frame(1000 * S).text());
        assertTrue(layout.unpin("stay"));
        assertFalse(layout.unpin("stay"));
        assertTrue(layout.isEmpty(1000 * S));
    }

    @Test
    public void theOldestFlashMessageGoesFirstWhenThereIsNoRoom() {
        Layout layout = layout();
        for (String text : new String[]{"1", "2", "3", "4"}) {
            layout.flash(text, null, 0, 0);
        }
        assertEquals("2\n3\n4", layout.frame(1).text());
    }

    @Test
    public void theBarFollowsTheMessageWithTheHighestPriorityAndFallsBackToTheBase() {
        Layout layout = layout();
        layout.base("info", bar(100, BarColor.WHITE), 100 * S, 0);
        assertEquals(bar(100, BarColor.WHITE), layout.frame(1).bar());
        layout.pin("cooldown", "cooldown", 1, bar(40, BarColor.RED), 5 * S, 0);
        layout.flash("notice", bar(10, BarColor.GREEN), 0, 0);
        assertEquals(bar(40, BarColor.RED), layout.frame(1).bar());
        assertEquals(bar(10, BarColor.GREEN), layout.frame(6 * S).bar());
        assertEquals(bar(100, BarColor.WHITE), layout.frame(16 * S).bar());
    }

    @Test
    public void aMessageWithoutBarLeavesTheBarAlone() {
        Layout layout = layout();
        layout.base("info", bar(70, BarColor.BLUE), 100 * S, 0);
        layout.flash("notice", null, 0, 0);
        assertEquals(bar(70, BarColor.BLUE), layout.frame(1).bar());
    }

    @Test
    public void theBaseGoesAwayWhenItIsNotSentAgain() {
        Layout layout = layout();
        layout.base("info", null, 2 * S, 0);
        layout.pin("a", "a", 0, null, 0, 0);
        assertEquals("info\na", layout.frame(1 * S).text());
        assertEquals("a", layout.frame(2 * S).text());
    }

    @Test
    public void limitsAreEnforced() {
        Layout layout = layout();
        layout.pin("a", "a", 0, null, 0, 0);
        layout.pin("b", "b", 0, null, 0, 0);
        assertThrows(IllegalStateException.class, () -> layout.pin("c", "c", 0, null, 0, 0));
        layout.pin("a", "again", 0, null, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> layout.flash("x".repeat(101), null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> layout.base("info", null, 0, 0));
    }

    @Test
    public void colorsAcceptNamesAliasesAndTheFallback() {
        Map<String, String> aliases = Map.of("cooldown", "red", "ready", "GREEN");
        assertEquals(BarColor.RED, BarColor.parse("cooldown", aliases, BarColor.WHITE));
        assertEquals(BarColor.GREEN, BarColor.parse("ready", aliases, BarColor.WHITE));
        assertEquals(BarColor.PURPLE, BarColor.parse(" Purple ", aliases, BarColor.WHITE));
        assertEquals(BarColor.WHITE, BarColor.parse("", aliases, BarColor.WHITE));
        assertEquals(BarColor.WHITE, BarColor.parse(null, aliases, BarColor.WHITE));
        assertThrows(IllegalArgumentException.class, () -> BarColor.parse("orange", aliases, BarColor.WHITE));
    }

    @Test
    public void theFillIsKeptBetweenZeroAndHundred() {
        assertEquals(100f, bar(250, BarColor.RED).fill(), 0f);
        assertEquals(0f, bar(-3, BarColor.RED).fill(), 0f);
        assertThrows(IllegalArgumentException.class, () -> bar(Float.NaN, BarColor.RED));
    }
}
