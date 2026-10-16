package dev.toka.display;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChannelTest {
 @Test public void priorityAndStableTie(){var c=new Channel<String>(4);c.put("zero",1,100,"base");c.put("portal",10,50,"teleport");assertEquals("teleport",c.winner(1).orElseThrow());assertEquals("base",c.winner(50).orElseThrow());c.put("area",1,100,"area");assertEquals("area",c.winner(51).orElseThrow());}
 @Test public void replaceAndRemove(){var c=new Channel<String>(1);c.put("zero",1,100,"old");c.put("zero",1,100,"new");assertEquals("new",c.winner(1).orElseThrow());c.remove("zero");assertTrue(c.winner(1).isEmpty());}
 @Test public void expiredHighPriorityDoesNotHideBase(){var c=new Channel<String>(2);c.put("base",0,100,"base");c.put("alert",100,20,"alert");assertEquals("base",c.winner(20).orElseThrow());assertTrue(c.winner(100).isEmpty());}
 @Test public void boundedSources(){var c=new Channel<String>(1);c.put("one",1,100,"a");assertThrows(IllegalStateException.class,()->c.put("two",2,100,"b"));}
}
