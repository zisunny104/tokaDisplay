package dev.toka.display;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnnouncementOneLineTest {
    @Test
    public void lineBreaksBecomeOneSpace() {
        assertEquals("一 二", AnnouncementService.oneLine("一\n二"));
        assertEquals("一 二", AnnouncementService.oneLine("一\r\n二"));
        assertEquals("一 二", AnnouncementService.oneLine("一\n二"));
        assertEquals("一 二 三", AnnouncementService.oneLine("一\n \n二 \n  三"));
    }

    @Test
    public void spacesAroundTheTextAreDropped() {
        assertEquals("一", AnnouncementService.oneLine("  一\n"));
    }

    @Test
    public void theLongestBroadcastLeavesRoomForTheFirstLine() {
        // a boss bar text of 380 bytes at most: the first line of the base is about 150 bytes with its spaces
        assertTrue(AnnouncementService.MAX_BROADCAST_BYTES + 150 + 20 <= Layout.MAX_BYTES);
    }
}
