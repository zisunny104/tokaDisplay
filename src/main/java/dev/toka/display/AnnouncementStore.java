package dev.toka.display;

import com.google.gson.*;
import dev.toka.storage.*;
import java.io.IOException;
import java.util.*;

/** One saved document; no active snapshot changes before persistence succeeds. Writes may run on the storage thread, so they are synchronized and the snapshot is swapped whole. */
public final class AnnouncementStore implements AutoCloseable {
    private final DocumentStore store;
    private volatile JsonObject state;
    public AnnouncementStore(StoreConfig config) throws IOException {
        store=DocumentStore.open(config);
        try{state=store.load("announcements").orElse(null);if(state!=null)validate(state);}
        catch(IOException|RuntimeException error){try{store.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    private static void kind(String kind){if(!Set.of("motd","broadcast").contains(kind))throw new IllegalArgumentException("Unknown kind");}
    private static void validate(JsonObject state){
        if(state.get("schema").getAsInt()!=1)throw new IllegalArgumentException("Unknown schema");
        for(String kind:List.of("motd","broadcast")){
            JsonArray list=state.getAsJsonArray(kind);if(list==null||list.size()>10000)throw new IllegalArgumentException("Invalid list");
            Set<Integer> ids=new HashSet<>();
            for(var element:list){var row=element.getAsJsonObject();int id=row.get("id").getAsBigDecimal().intValueExact();String text=row.get("text").getAsString();
                if(id<0||!ids.add(id)||text.isBlank()||text.length()>8192)throw new IllegalArgumentException("Invalid announcement");}
        }
    }
    public JsonArray list(String kind){kind(kind);return state==null?new JsonArray():state.getAsJsonArray(kind).deepCopy();}
    public boolean initialized(){return state!=null;}
    public int count(String kind){kind(kind);return state==null?0:state.getAsJsonArray(kind).size();}
    /** @return the text at a position of the list (wrapped around), without copying the list; null when the list is empty */
    public String textAt(String kind,int index){
        kind(kind);JsonObject current=state;if(current==null)return null;
        JsonArray list=current.getAsJsonArray(kind);
        return list.isEmpty()?null:list.get(Math.floorMod(index,list.size())).getAsJsonObject().get("text").getAsString();
    }
    private void save(JsonObject next)throws IOException{validate(next);store.save("announcements",next);JsonObject actual=store.load("announcements").orElseThrow(()->new IOException("Missing announcement readback"));if(!actual.equals(next))throw new IOException("Announcement readback mismatch");state=next;}
    public synchronized int change(String kind,String action,int id,String text)throws IOException{
        kind(kind);JsonObject next;
        if(state==null){next=new JsonObject();next.addProperty("schema",1);next.add("motd",new JsonArray());next.add("broadcast",new JsonArray());}else next=state.deepCopy();
        var list=next.getAsJsonArray(kind);
        if(action.equals("add")){
            long counter=next.has(kind+"NextId")?next.get(kind+"NextId").getAsLong():0;
            for(var row:list)counter=Math.max(counter,(long)row.getAsJsonObject().get("id").getAsInt()+1);
            if(counter>=Integer.MAX_VALUE)throw new IllegalStateException("Id overflow");id=(int)counter;next.addProperty(kind+"NextId",counter+1);
            var row=new JsonObject();row.addProperty("id",id);row.addProperty("text",text);list.add(row);
        }else{
            int index=-1;for(int i=0;i<list.size();i++)if(list.get(i).getAsJsonObject().get("id").getAsInt()==id)index=i;
            if(index<0)throw new IllegalArgumentException("Unknown id");
            if(action.equals("remove"))list.remove(index);else if(action.equals("set"))list.get(index).getAsJsonObject().addProperty("text",text);else throw new IllegalArgumentException("Unknown action");
        }
        save(next);return id;
    }
    public void close()throws IOException{store.close();}
}
