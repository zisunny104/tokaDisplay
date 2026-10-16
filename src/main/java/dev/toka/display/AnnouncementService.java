package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.storage.*;
import java.util.*;
import java.nio.file.*;

/** Keeps publication off while zero's old schedules remain active. */
public final class AnnouncementService implements AutoCloseable {
    private final Main plugin;
    private final AnnouncementStore store;
    private final String initialMotd;
    private long nextMotd,nextBroadcast;
    private int motdIndex,broadcastIndex;
    private boolean changedMotd,warned;
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
    public boolean publishing(){return plugin.getConfig().getBoolean("announcements.enabled",false)&&store.initialized()&&!legacyActive();}
    public String list(String kind){return store.list(kind).toString();}
    public int change(String kind,String action,int id,String text)throws Exception{return store.change(kind,action,id,text.replace("\\n","\n"));}
    public void importLegacy(String payload)throws Exception{
        var directory=plugin.getDataFolder().toPath().resolve("migration-backups");Files.createDirectories(directory);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var backup=directory.resolve(hash+".json");
        if(!Files.exists(backup))Files.writeString(backup,payload,StandardOpenOption.CREATE_NEW);
        else if(!Files.readString(backup).equals(payload))throw new IllegalStateException("Backup mismatch");
        store.importLegacy(payload);
    }
    public void requestEnable(){
        boolean previous=plugin.getConfig().getBoolean("announcements.enabled",false);
        plugin.getConfig().set("announcements.enabled",true);
        if(!plugin.getConfig().save()){plugin.getConfig().set("announcements.enabled",previous);throw new IllegalStateException("Failed to save configuration");}
    }
    private long interval(String kind,int fallback){return Math.max(1,Math.min(86400,plugin.getConfig().getInt("announcements."+kind+"-seconds",fallback)))*1_000_000_000L;}
    public void pulse(){
        if(!publishing()){
            if(plugin.getConfig().getBoolean("announcements.enabled",false)&&legacyActive()&&!warned){warned=true;plugin.getLogger().warning("zero 的 MOTD／廣播尚未停用，Display 暫不發布，避免雙重排程。");}
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
        String kind=args[0];if(!Set.of("motd","broadcast","announcements").contains(kind))return false;
        String permission="tokaDisplay."+kind+".manage";
        if(!sender.hasPermission(permission)){sender.sendMessage("沒有此項管理權限");return true;}
        try{
            if(kind.equals("announcements")){
                if(args.length!=2||!args[1].equals("enable"))throw new IllegalArgumentException();
                if(legacyActive()){sender.sendMessage("請先用 /zero display-migrate 搬遷並完整重啟，避免雙重排程。");return true;}
                requestEnable();sender.sendMessage("公告發布已啟用");return true;
            }
            if(args.length<2)throw new IllegalArgumentException();
            if(args[1].equals("list")){sender.sendMessage(list(kind));return true;}
            int offset=args[1].equals("add")?2:3;
            int id=args[1].equals("add")?-1:Integer.parseInt(args[2]);
            String text=String.join(" ",Arrays.copyOfRange(args,Math.min(offset,args.length),args.length));
            sender.sendMessage("已儲存 "+kind+" #"+change(kind,args[1],id,text));
        }catch(Exception e){sender.sendMessage("操作未完成（"+e.getClass().getSimpleName()+"）：/display motd|broadcast list|add <文字>|set <ID> <文字>|remove <ID>");}
        return true;
    }
    public void close()throws Exception{if(changedMotd)plugin.getServer().getNetwork().setName(initialMotd);store.close();}
}
