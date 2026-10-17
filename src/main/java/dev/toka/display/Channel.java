package dev.toka.display;

import java.util.*;

/** Source-keyed arbitration. Stable tie breaking; deadlines use monotonic time. */
public final class Channel<T> {
    private record Entry<T>(int priority,long expires,T value) {}
    private final Map<String,Entry<T>> entries=new HashMap<>();
    private final int limit;
    public Channel(int limit){if(limit<1)throw new IllegalArgumentException("limit");this.limit=limit;}
    public void put(String source,int priority,long expires,T value){
        if(source==null||source.isBlank()||source.length()>128)throw new IllegalArgumentException("source");
        if(!entries.containsKey(source)&&entries.size()>=limit)throw new IllegalStateException("Too many display sources");
        entries.put(source,new Entry<>(priority,expires,Objects.requireNonNull(value)));
    }
    public Optional<T> winner(long now){
        entries.values().removeIf(e->e.expires<=now);
        String bestKey=null;Entry<T> best=null;
        for(var e:entries.entrySet()){
            Entry<T> v=e.getValue();
            if(best==null||v.priority>best.priority||(v.priority==best.priority&&e.getKey().compareTo(bestKey)<0)){best=v;bestKey=e.getKey();}
        }
        return Optional.ofNullable(best).map(Entry::value);
    }
    public void remove(String source){entries.remove(source);}
    public boolean empty(){return entries.isEmpty();}
}
