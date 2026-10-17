package dev.toka.display;

import com.google.gson.*;
import dev.toka.storage.*;
import java.io.IOException;
import java.util.*;

/** One saved document; no active snapshot changes before persistence succeeds. */
public final class AnnouncementStore implements AutoCloseable {
    private final DocumentStore store;
    private JsonObject state;
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
    private static JsonObject content(JsonObject input){
        validate(input);JsonObject out=new JsonObject();out.addProperty("schema",1);
        for(String kind:List.of("motd","broadcast")){
            TreeMap<Integer,JsonObject> rows=new TreeMap<>();
            for(var value:input.getAsJsonArray(kind)){var row=value.getAsJsonObject();JsonObject clean=new JsonObject();clean.addProperty("id",row.get("id").getAsInt());clean.addProperty("text",row.get("text").getAsString());rows.put(clean.get("id").getAsInt(),clean);}
            JsonArray list=new JsonArray();rows.values().forEach(list::add);out.add(kind,list);
        }return out;
    }
    public String snapshotJson(){if(state!=null)return content(state).toString();return "{\"schema\":1,\"motd\":[],\"broadcast\":[]}";}
    public boolean verify(String json){return JsonParser.parseString(snapshotJson()).equals(content(JsonParser.parseString(json).getAsJsonObject()));}
    /** General snapshot initialization: accept an empty destination or identical current content only. */
    public void replaceIfEmptyOrEqual(String json)throws IOException{
        JsonObject incoming=content(JsonParser.parseString(json).getAsJsonObject());
        if(state!=null&&(!state.getAsJsonArray("motd").isEmpty()||!state.getAsJsonArray("broadcast").isEmpty())){
            if(!content(state).equals(incoming))throw new IllegalStateException("Destination differs; refusing overwrite");return;
        }
        JsonObject next=incoming.deepCopy();
        for(String kind:List.of("motd","broadcast")){long id=state!=null&&state.has(kind+"NextId")?state.get(kind+"NextId").getAsLong():0;for(var row:incoming.getAsJsonArray(kind))id=Math.max(id,(long)row.getAsJsonObject().get("id").getAsInt()+1);next.addProperty(kind+"NextId",id);}
        save(next);
    }
    private void save(JsonObject next)throws IOException{validate(next);store.save("announcements",next);JsonObject actual=store.load("announcements").orElseThrow(()->new IOException("Missing announcement readback"));if(!actual.equals(next))throw new IOException("Announcement readback mismatch");state=next;}
    public int change(String kind,String action,int id,String text)throws IOException{
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
