package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.storage.*;
import java.util.*;


/** Publishes the MOTD and the broadcasts. It stays quiet while zero's old schedules are still on, so that nothing is sent twice. */
public final class AnnouncementService implements AutoCloseable {
    private final Main plugin;
    private final AnnouncementStore store;
    private final String initialMotd;
    private long nextMotd,nextBroadcast;
    private int motdIndex,broadcastIndex;
    private boolean changedMotd;
    public AnnouncementService(Main plugin)throws Exception{
        this.plugin=plugin;initialMotd=plugin.getServer().getNetwork().getName();
        var backend=Backend.valueOf(plugin.getConfig().getString("storage.backend","sqlite").toUpperCase(Locale.ROOT));
        store=new AnnouncementStore(new StoreConfig(backend,plugin.getDataFolder().toPath().resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT)),1));
    }
    public boolean legacyActive(){
        var zero=plugin.getServer().getPluginManager().getPlugin("zero");
        if(zero==null||!zero.isEnabled())return false;
        try{return (Boolean)zero.getClass().getMethod("isModuleActive",String.class).invoke(zero,"motd")||(Boolean)zero.getClass().getMethod("isModuleActive",String.class).invoke(zero,"broadcast");}
        catch(Exception|LinkageError e){return true;}
    }
    /** @return the number of MOTD and of broadcasts that are loaded */
    public int[] counts(){return new int[]{store.list("motd").size(),store.list("broadcast").size()};}
    public boolean publishing(){return plugin.getConfig().getBoolean("announcements.enabled",true)&&store.initialized()&&!legacyActive();}
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
        if(!publishing()){
            return;
        }
        long now=System.nanoTime();var motds=store.list("motd");var broadcasts=store.list("broadcast");
        if(motds.size()==0&&changedMotd){plugin.getServer().getNetwork().setName(initialMotd);changedMotd=false;}
        if(now>=nextMotd&&motds.size()>0){
            plugin.getServer().getNetwork().setName(motds.get(Math.floorMod(motdIndex++,motds.size())).getAsJsonObject().get("text").getAsString());
            changedMotd=true;nextMotd=now+interval("motd",10);
        }
        if(now>=nextBroadcast&&broadcasts.size()>0&&!plugin.getServer().getOnlinePlayers().isEmpty()){
            String text=broadcasts.get(Math.floorMod(broadcastIndex++,broadcasts.size())).getAsJsonObject().get("text").getAsString();
            for(Player player:plugin.getServer().getOnlinePlayers().values())Main.flash(player,text,0,-1f,null);
            nextBroadcast=now+interval("broadcast",25);
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
            sender.sendMessage(plugin.messages().text("msg.announce.saved",kind,change(kind,args[1],id,text)));
        }catch(Exception e){sender.sendMessage(plugin.messages().text("err.announceFailed",e.getClass().getSimpleName())+plugin.messages().text("cmd.display.announce.usage"));}
        return true;
    }
    public void close()throws Exception{if(changedMotd)plugin.getServer().getNetwork().setName(initialMotd);store.close();}
}
