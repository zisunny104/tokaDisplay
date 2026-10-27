package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.storage.*;
import java.util.*;
import java.util.concurrent.*;


/** Publishes the MOTD and the broadcasts. */
public final class AnnouncementService implements AutoCloseable {
    private final Main plugin;
    private final AnnouncementStore store;
    private final String initialMotd;
    private long nextMotd,nextBroadcast;
    private int motdIndex,broadcastIndex;
    private boolean changedMotd;
    /** Saving and reading back the storage happens here, one task at a time, never on the server thread. */
    private final ExecutorService writer=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"tokaDisplay-storage");thread.setDaemon(true);return thread;});
    public AnnouncementService(Main plugin)throws Exception{
        this.plugin=plugin;initialMotd=plugin.getServer().getNetwork().getName();
        var backend=Backend.valueOf(plugin.getConfig().getString("storage.backend","sqlite").toUpperCase(Locale.ROOT));
        store=new AnnouncementStore(new StoreConfig(backend,plugin.getDataFolder().toPath().resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT)),1));
    }
    /** @return the number of MOTD and of broadcasts that are loaded */
    public int[] counts(){return new int[]{store.count("motd"),store.count("broadcast")};}
    public boolean publishing(){return plugin.getConfig().getBoolean("announcements.enabled",true)&&store.initialized();}
    /** The text of the entry with this id, or an empty string */
    private String textOf(String kind,int id){
        for(var row:store.list(kind)){var o=row.getAsJsonObject();if(o.get("id").getAsInt()==id)return o.get("text").getAsString();}
        return "";
    }
    /** The saved entries for the window; each row has id and text. */
    com.google.gson.JsonArray rows(String kind){return store.list(kind);}
    public String list(String kind){return store.list(kind).toString();}
    /** The most a broadcast may be in UTF-8 bytes: with the first line of the boss bar it has to stay under what the client takes. */
    static final int MAX_BROADCAST_BYTES=200;
    /** A broadcast is one line: the line breaks, typed as \n or in the old saved ones, become one space. */
    static String oneLine(String text){return text.replace("\\n"," ").replaceAll("\\s*[\\r\\n]+\\s*"," ").strip();}
    public int change(String kind,String action,int id,String text)throws Exception{return store.change(kind,action,id,kind.equals("broadcast")?oneLine(text):text.replace("\\n","\n"));}
    private long interval(String kind,int fallback){return Math.max(1,Math.min(86400,plugin.getConfig().getInt("announcements."+kind+"-seconds",fallback)))*1_000_000_000L;}
    public void pulse(){
        if(!publishing())return;
        long now=System.nanoTime();int motds=store.count("motd"),broadcasts=store.count("broadcast");
        if(motds==0&&changedMotd){plugin.getServer().getNetwork().setName(initialMotd);changedMotd=false;}
        if(now>=nextMotd&&motds>0){
            String text=store.textAt("motd",motdIndex++);
            if(text!=null){plugin.getServer().getNetwork().setName(text);changedMotd=true;nextMotd=now+interval("motd",10);}
        }
        if(now>=nextBroadcast&&broadcasts>0&&!plugin.getServer().getOnlinePlayers().isEmpty()){
            String text=store.textAt("broadcast",broadcastIndex++);
            if(text!=null){
                String line=oneLine(text);
                for(Player player:plugin.getServer().getOnlinePlayers().values())Main.flash(player,line,0,-1f,null);
                nextBroadcast=now+interval("broadcast",25);
            }
        }
    }
    public boolean command(CommandSender sender,String[] args){
        if(args.length==0)return false;
        String kind=args[0];if(!Set.of("motd","broadcast").contains(kind))return false;
        String permission="tokaDisplay."+kind+".manage";
        if(!sender.hasPermission(permission)){sender.sendMessage(plugin.messages().text("err.noManagePermission"));return true;}
        try{
            if(args.length<2)throw new IllegalArgumentException();
            if(args[1].equals("list")){sender.sendMessage(list(kind));return true;}
            int offset=args[1].equals("add")?2:3;
            int id=args[1].equals("add")?-1:Integer.parseInt(args[2]);
            String text=String.join(" ",Arrays.copyOfRange(args,Math.min(offset,args.length),args.length));
            String action=args[1];
            writer.execute(()->{
                try{reply(sender,perform(kind,action,id,text));}
                catch(Exception e){fail(sender,e);}
            });
        }catch(Exception e){fail(sender,e);}
        return true;
    }
    /** Saves one change on the storage thread and returns the line that tells what was done. */
    private String perform(String kind,String action,int id,String text)throws Exception{
        String before=textOf(kind,id);
        if(kind.equals("broadcast")&&!action.equals("remove")){
            int bytes=oneLine(text).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes>MAX_BROADCAST_BYTES)return plugin.messages().text("err.broadcastTooLong",bytes,MAX_BROADCAST_BYTES);
        }
        int saved=change(kind,action,id,text);
        String shown=action.equals("remove")?before:kind.equals("broadcast")?oneLine(text):text;
        return plugin.messages().text(action.equals("add")?"msg.announce.added":action.equals("set")?"msg.announce.changed":"msg.announce.removed",kind,saved,shown.replace((char)10,(char)32));
    }
    /** For the window: the same change as the commands make; the answer comes back on the server thread, the line or the failure. */
    void submit(String kind,String action,int id,String text,java.util.function.BiConsumer<String,Exception> done){
        try{
            writer.execute(()->{
                String line=null;Exception failure=null;
                try{line=perform(kind,action,id,text);}
                catch(Exception e){failure=e;plugin.getLogger().warning(plugin.messages().text("log.announceSaveFailed",e.getClass().getSimpleName()));}
                String shownLine=line;Exception shownFailure=failure;
                try{plugin.getServer().getScheduler().scheduleTask(plugin,()->done.accept(shownLine,shownFailure));}catch(RuntimeException ignored){}
            });
        }catch(RejectedExecutionException closing){done.accept(null,closing);}
    }
    private void fail(CommandSender sender,Exception e){
        plugin.getLogger().warning(plugin.messages().text("log.announceSaveFailed",e.getClass().getSimpleName()));
        reply(sender,plugin.messages().text("err.announceFailed",e.getClass().getSimpleName())+plugin.messages().text("cmd.display.announce.usage"));
    }
    /** Nukkit is only used on the server thread; the server may already be stopping when the storage thread ends. */
    private void reply(CommandSender sender,String line){
        try{plugin.getServer().getScheduler().scheduleTask(plugin,()->sender.sendMessage(line));}catch(RuntimeException ignored){}
    }
    /** Waits for the saves that are queued, so that none is lost. */
    public void close()throws Exception{
        writer.shutdown();
        if(!writer.awaitTermination(3,TimeUnit.SECONDS))plugin.getLogger().warning(plugin.messages().text("log.storageDrainTimeout"));
        if(changedMotd)plugin.getServer().getNetwork().setName(initialMotd);store.close();
    }
}
