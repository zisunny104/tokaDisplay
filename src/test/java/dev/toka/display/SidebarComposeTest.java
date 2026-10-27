package dev.toka.display;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SidebarComposeTest {
    private static SidebarCompose.Result run(List<String> layout,Map<String,String> sections,int max){
        return SidebarCompose.compose(layout,id->{
            if("boom".equals(id))throw new IllegalStateException();
            return sections.get(id);
        },max);
    }
    @Test public void skipsEmptyAndUnregistered(){
        var r=run(List.of("a","none","b","empty"),Map.of("a","A","b","B","empty",""),15);
        assertEquals(List.of("A","B"),r.lines());
        assertEquals(List.of(1,2),r.scores());
    }
    @Test public void collapsesSeparators(){
        var r=run(List.of("-","a","-","none","-","-","b","-"),Map.of("a","A","b","B"),15);
        assertEquals(List.of("A"," ","B"),r.lines());
    }
    @Test public void limitDropsTheRestAndTrimsTail(){
        var r=run(List.of("a","b","-","c","d"),Map.of("a","A","b","B","c","C","d","D"),3);
        assertEquals(List.of("A","B"),r.lines());
        assertTrue(r.truncated());
        assertFalse(run(List.of("a"),Map.of("a","A"),1).truncated());
    }
    @Test public void throwingSectionIsSkippedAndReported(){
        var r=run(List.of("a","boom","b","boom"),Map.of("a","A","b","B"),15);
        assertEquals(List.of("A","B"),r.lines());
        assertEquals(List.of("boom"),r.failed());
    }
    @Test public void repeatedLinesStayUnique(){
        var r=run(List.of("a","-","a","-","a"),Map.of("a","A"),15);
        assertEquals(5,new HashSet<>(r.lines()).size());
    }
    @Test public void emptyLayoutGivesNothing(){
        assertTrue(run(List.of(),Map.of(),15).lines().isEmpty());
        assertTrue(run(List.of("-","-"),Map.of(),15).lines().isEmpty());
    }
    @Test public void aSectionThatBreaksItsLineUsesTheEmptyLineThatFollows(){
        // the item is wide: its second part takes the place of the empty line, so the count of the lines stays
        var r=run(List.of("a","item","-","b"),Map.of("a","A","item","HEAD\nname","b","B"),15);
        assertEquals(List.of("A","HEAD","name","B"),r.lines());
        assertEquals(List.of(1,2,3,4),r.scores());
        // without the break the same layout has the same count: the item, an empty line
        assertEquals(4,run(List.of("a","item","-","b"),Map.of("a","A","item","HEAD","b","B"),15).lines().size());
    }
    @Test public void aBrokenLineWithoutAnEmptyLineAfterItStaysInOneLine(){
        var r=run(List.of("a","item","b"),Map.of("a","A","item","HEAD\nname","b","B"),15);
        assertEquals(List.of("A","HEAD name","B"),r.lines());
    }
}
