package dev.toka.display;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.toka.storage.*;
import java.nio.file.*;
public class AnnouncementStoreTest {
 private static final String DATA="{\"schema\":1,\"motd\":[{\"id\":4,\"text\":\"繁體標題\"}],\"broadcast\":[{\"id\":9,\"text\":\"公告\\n第二行\"}]}";
 @Test public void allBackendsPreserveIdsAndReopen()throws Exception{
  for(Backend backend:Backend.values()){
   var config=new StoreConfig(backend,Files.createTempDirectory(Path.of("target"),"display-"),1);
   try(var store=new AnnouncementStore(config)){store.replaceIfEmptyOrEqual(DATA);store.change("motd","remove",4,"");assertEquals(5,store.change("motd","add",-1,"新標題"));assertThrows(IllegalStateException.class,()->store.replaceIfEmptyOrEqual(DATA));assertEquals("新標題",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());}
   try(var store=new AnnouncementStore(config)){assertEquals(5,store.list("motd").get(0).getAsJsonObject().get("id").getAsInt());assertEquals(9,store.list("broadcast").get(0).getAsJsonObject().get("id").getAsInt());}
  }
 }
 @Test public void destinationConflictAndInvalidInputDoNotOverwrite()throws Exception{
  var config=new StoreConfig(Backend.SQLITE,Files.createTempDirectory(Path.of("target"),"display-conflict-"),1);
  try(var store=new AnnouncementStore(config)){store.replaceIfEmptyOrEqual(DATA);assertThrows(IllegalStateException.class,()->store.replaceIfEmptyOrEqual(DATA.replace("繁體標題","其他標題")));assertThrows(IllegalArgumentException.class,()->store.change("motd","set",4," "));assertEquals("繁體標題",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());}
 }
 @Test public void duplicateIdsAreRejected()throws Exception{
  var config=new StoreConfig(Backend.JSON,Files.createTempDirectory(Path.of("target"),"display-duplicate-"),1);
  try(var store=new AnnouncementStore(config)){assertThrows(IllegalArgumentException.class,()->store.replaceIfEmptyOrEqual("{\"schema\":1,\"motd\":[{\"id\":1,\"text\":\"a\"},{\"id\":1,\"text\":\"b\"}],\"broadcast\":[]}"));assertFalse(store.initialized());}
 }
 @Test public void genericSnapshotsVerifyAndReadOldSourceWithoutTreatingItAsCurrent()throws Exception{
  var config=new StoreConfig(Backend.JSON,Files.createTempDirectory(Path.of("target"),"display-source-"),1);
  var old=com.google.gson.JsonParser.parseString(DATA).getAsJsonObject();old.add("legacySource",old.deepCopy());
  try(var documents=DocumentStore.open(config)){documents.save("announcements",old);}
  try(var store=new AnnouncementStore(config)){
   assertTrue(store.verify(DATA));store.replaceIfEmptyOrEqual(DATA);
   store.change("motd","set",4,"已修改");assertFalse(store.verify(DATA));
   assertThrows(IllegalStateException.class,()->store.replaceIfEmptyOrEqual(DATA));
   assertEquals("已修改",store.list("motd").get(0).getAsJsonObject().get("text").getAsString());
   assertFalse(store.snapshotJson().contains("legacySource"));
  }
 }
}
