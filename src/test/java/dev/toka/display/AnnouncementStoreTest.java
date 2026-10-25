package dev.toka.display;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.toka.storage.*;
import java.nio.file.*;
public class AnnouncementStoreTest {
 private static final String DATA="{\"schema\":1,\"motd\":[{\"id\":4,\"text\":\"繁體標題\"}],\"broadcast\":[{\"id\":9,\"text\":\"公告\n第二行\"}],\"motdNextId\":5,\"broadcastNextId\":10}";
 private static StoreConfig seeded(Backend backend,String prefix,String json)throws Exception{
  var config=new StoreConfig(backend,Files.createTempDirectory(Path.of("target"),prefix),1);
  try(var documents=DocumentStore.open(config)){documents.save("announcements",com.google.gson.JsonParser.parseString(json).getAsJsonObject());}
  return config;
 }
 @Test public void allBackendsPreserveIdsAndReopen()throws Exception{
  for(Backend backend:Backend.values()){
   var config=seeded(backend,"display-",DATA);
   try(var store=new AnnouncementStore(config)){store.change("motd","remove",4,"");assertEquals(5,store.change("motd","add",-1,"新標題"));assertEquals("新標題",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());}
   try(var store=new AnnouncementStore(config)){assertEquals(5,store.list("motd").get(0).getAsJsonObject().get("id").getAsInt());assertEquals(9,store.list("broadcast").get(0).getAsJsonObject().get("id").getAsInt());}
  }
 }
 @Test public void invalidInputDoesNotOverwrite()throws Exception{
  var config=seeded(Backend.SQLITE,"display-conflict-",DATA);
  try(var store=new AnnouncementStore(config)){assertThrows(IllegalArgumentException.class,()->store.change("motd","set",4," "));assertEquals("繁體標題",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());}
 }
 @Test public void duplicateIdsAreRejected()throws Exception{
  var config=seeded(Backend.JSON,"display-duplicate-","{\"schema\":1,\"motd\":[{\"id\":1,\"text\":\"a\"},{\"id\":1,\"text\":\"b\"}],\"broadcast\":[]}");
  assertThrows(IllegalArgumentException.class,()->new AnnouncementStore(config));
 }
}
