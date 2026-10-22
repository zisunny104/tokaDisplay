package dev.toka.display;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class NameTagTest {
    private static final String LEGACY = "${prefix}§r${affiliation}§r${mode}[${title}${mode}${name}${mode}]";

    @Test public void partsFillTheirPlaces() {
        var values = Map.of("prefix", "§c[管]", "affiliation", "§a[和平村]", "mode", "§a", "title", "村長§6-", "name", "Alex");
        assertEquals("§c[管]§r§a[和平村]§r§a[村長§6-§aAlex§a]", new NameTag(LEGACY).compose(values::get));
    }

    @Test public void aPartNobodyProvidesIsJustEmpty() {
        assertEquals("§r§r§7[§7Alex§7]", new NameTag(LEGACY).compose(Map.of("mode", "§7", "name", "Alex")::get));
    }

    @Test public void textWithoutPlaceholdersAndBrokenOnesPassThrough() {
        assertEquals("plain", new NameTag("plain").compose(id -> "x"));
        assertEquals("a ${open", new NameTag("a ${open").compose(id -> "x"));
        assertEquals("", new NameTag("").compose(id -> "x"));
    }

    @Test public void aReplacementIsNotExpandedAgain() {
        assertEquals("${name}", new NameTag("${title}").compose(id -> id.equals("title") ? "${name}" : "Alex"));
    }

    @Test public void theSameIdCanAppearSeveralTimes() {
        assertEquals("A-A", new NameTag("${n}-${n}").compose(id -> "A"));
    }
}
