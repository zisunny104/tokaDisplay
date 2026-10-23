package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.storage.*;
import java.util.*;
import java.util.concurrent.*;


/** Publishes the MOTD and the broadcasts. It stays quiet while zero's old schedules are still on, so that nothing is sent twice. */
public final class AnnouncementService implements AutoCloseable {
    private final Main plugin;
    private final AnnouncementStore store;
    private final String initialMotd;
    private long nextMotd,nextBroadcast;
    private int motdIndex,broadcastIndex;
    private boolean changedMotd;
    private java.lang.reflect.Method moduleCheck;
    /** Saving and reading back the storage happens here, one task at a time, never on the server thread. */
    private final ExecutorService writer=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"tokaDisplay-storage");thread.setDaemon(true);return thread;});
    public AnnouncementService(Main plugin)throws Exception{
        this.plugin=plugin;initialMotd=plugin.getServer().getNetwork().getName();
        var backend=Backend.valueOf(plugin.getConfig().getString("storage.backend","sqlite").toUpperCase(Locale.ROOT));
        store=new AnnouncementStore(new StoreConfig(backend,plugin.getDataFolder().toPath().resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT)),1));
    }
    private boolean legacyActive(){
        var zero=plugin.getServer().getPluginManager().getPlugin("zero");
        if(zero==null||!zero.isEnabled())return false;
        try{
            if(moduleCheck==null||moduleCheck.getDeclaringClass()!=zero.getClass())moduleCheck=zero.getClass().getMethod("isModuleActive",String.class);
            return (Boolean)moduleCheck.invoke(zero,"motd")||(Boolean)moduleCheck.invoke(zero,"broadcast");
        }
        catch(Exception|LinkageError e){return true;}
    }
    /** @return the number of MOTD and of broadcasts that are loaded */
    public int[] counts(){return new int[]{store.count("motd"),store.count("broadcast")};}
    public boolean publishing(){return plugin.getConfig().getBoolean("announcements.enabled",true)&&store.initialized()&&!legacyActive();}
    /** The text of the entry with this id, or an empty string */
    private String textOf(String kind,int id){
        for(var row:store.list(kind)){var o=row.getAsJsonObject();if(o.get("id").getAsInt()==id)return o.get("text").getAsString();}
        return "";
    }
    public String list(String kind){return store.list(kind).toString();}
    public int change(String kind,String action,int id,String text)throws Exception{return store.change(kind,action,id,text.replace("\\n","\n"));}
    public void replace(String payload)throws Exception{store.replaceIfEmptyOrEqual(payload);}
    public String snapshot(){return store.snapshotJson();}
    public boolean verify(String payload){return store.verify(payload);}
    public void setEnabled(boolean enabled){
        boolean previous=plugin.getConfig().getBoolean("announcements.enabled",true);
        plugin.getConfig().set("announcements.enabled",enabled);
        try{plugin.saveConfig();}catch(RuntimeException error){plugin.getConfig().set("announcements.enabled",previous);throw error;}
        if(!enabled&&changedMotd){plugin.getServer().getNetwork().setName(initialMotd);changedMotd=false;}
    }
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
                for(Player player:plugin.getServer().getOnlinePlayers().values())Main.flash(player,text,0,-1f,null);
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
                try{
                    String before=textOf(kind,id);
                    int saved=change(kind,action,id,text);
                    String shown=action.equals("remove")?before:text;
                    reply(sender,plugin.messages().text("msg.announce."+(action.equals("add")?"added":action.equals("set")?"changed":"removed"),kind,saved,shown.replace("
"," ")));
                }
                catch(Exception e){fail(sender,e);}
            });
        }catch(Exception e){fail(sender,e);}
        return true;
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
        if(!writer.awaitTermination(10,TimeUnit.SECONDS))plugin.getLogger().warning(plugin.messages().text("log.storageDrainTimeout"));
        if(changedMotd)plugin.getServer().getNetwork().setName(initialMotd);store.close();
    }
}
