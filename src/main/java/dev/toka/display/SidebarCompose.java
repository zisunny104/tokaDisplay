package dev.toka.display;

import java.util.*;
import java.util.function.Function;

/** Builds the sidebar lines from the layout list. Pure, so it can be tested without a server. */
public final class SidebarCompose {
    /** The layout item that stands for an empty separator line. */
    public static final String SEPARATOR="-";
    public record Result(List<String> lines,List<Integer> scores,List<String> failed,boolean truncated) {}
    private SidebarCompose(){}
    /**
     * @param lookup the line of a section id, null or empty when it is not shown; may throw, that section is skipped
     * @param max    the most lines the sidebar takes; the rest is dropped
     */
    public static Result compose(List<String> layout,Function<String,String> lookup,int max){
        List<String> items=new ArrayList<>(),failed=new ArrayList<>();
        for(int i=0;i<layout.size();i++){
            String id=layout.get(i);
            if(SEPARATOR.equals(id)){if(!items.isEmpty()&&items.get(items.size()-1)!=null)items.add(null);continue;}
            String text=null;
            try{text=lookup.apply(id);}catch(RuntimeException|LinkageError error){if(!failed.contains(id))failed.add(id);}
            if(text==null||text.isEmpty())continue;
            int newline=text.indexOf('\n');
            if(newline<0){items.add(text);continue;}
            // A section that is too wide for its line gives the rest after a line break: the rest takes the place of the empty
            // line that follows it, so the sidebar has no line more; without such a line the two parts stay in one line
            String head=text.substring(0,newline),rest=text.substring(newline+1);
            if(rest.isEmpty())items.add(head);
            else if(i+1<layout.size()&&SEPARATOR.equals(layout.get(i+1))){items.add(head);items.add(rest);i++;}
            else items.add(head+" "+rest);
        }
        boolean truncated=items.size()>max;
        if(truncated)items=new ArrayList<>(items.subList(0,max));
        while(!items.isEmpty()&&items.get(items.size()-1)==null)items.remove(items.size()-1);
        List<String> lines=new ArrayList<>();List<Integer> scores=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String item:items){
            String line=item==null?" ":item;
            // The board keeps one score for each text, so a repeated line gets an invisible tail
            while(!seen.add(line))line+="§r";
            lines.add(line);scores.add(lines.size());
        }
        return new Result(lines,scores,failed,truncated);
    }
}
