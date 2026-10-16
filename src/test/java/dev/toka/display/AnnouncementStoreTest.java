package dev.toka.display;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.toka.storage.*;
import java.nio.file.*;
public class AnnouncementStoreTest {
 private static final String DATA="{\"schema\":1,\"motd\":[{\"id\":4,\"text\":\"繁體標題\"}],\"broadcast\":[{\"id\":9,\"text\":\"公告\\n第二行\"}]}";
 @Test public void allBackendsPreserveIdsAndReopen()throws Exception{
  for(Backend backend:Backend.values()){
   var config=new StoreConfig(backend,Files.createTempDirectory("display-"),1);
   try(var store=new AnnouncementStore(config)){store.importLegacy(DATA);store.change("motd","remove",4,"");assertEquals(5,store.change("motd","add",-1,"新標題"));store.importLegacy(DATA);assertEquals("新標題",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());}
   try(var store=new AnnouncementStore(config)){assertEquals(5,store.list("motd").get(0).getAsJsonObject().get("id").getAsInt());assertEquals(9,store.list("broadcast").get(0).getAsJsonObject().get("id").getAsInt());}
  }
 }
 @Test public void destinationConflictAndInvalidInputDoNotOverwrite()throws Exception{
  var config=new StoreConfig(Backend.SQLITE,Files.createTempDirectory("display-conflict-"),1);
  try(var store=new AnnouncementStore(config)){store.importLegacy(DATA);assertThrows(IllegalStateException.class,()->store.importLegacy(DATA.replace("繁體標題","其他標題")));assertThrows(IllegalArgumentException.class,()->store.change("motd","set",4," "));assertEquals("繁體標題",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());}
 }
 @Test public void duplicateLegacyIdsAreRejected()throws Exception{
  var config=new StoreConfig(Backend.JSON,Files.createTempDirectory("display-duplicate-"),1);
  try(var store=new AnnouncementStore(config)){assertThrows(IllegalArgumentException.class,()->store.importLegacy("{\"schema\":1,\"motd\":[{\"id\":1,\"text\":\"a\"},{\"id\":1,\"text\":\"b\"}],\"broadcast\":[]}"));assertFalse(store.initialized());}
 }
}
