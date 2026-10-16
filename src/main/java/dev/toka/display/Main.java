package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.BossBarColor;
import cn.nukkit.utils.DummyBossBar;
import cn.nukkit.scoreboard.Scoreboard;
import java.util.*;

/**
 * Shows the boss bar area and the sidebar for the other plugins.
 * <p>
 * The boss bar area has the base information on the first line, that only its owner can change, then the pinned
 * messages and then the messages that are sent once. A message can also change how full the bar is and its color.
 * Owns only its own bars/boards, never clears another plugin's display objects.
 */
public final class Main extends PluginBase implements Listener {
    /** The owner of the base information, the only source that is not a pinned message. */
    public static final String BASE_SOURCE = "zero:status";

    public record Sidebar(String title,List<String> lines,List<Integer> scores) {
        public Sidebar { lines=List.copyOf(lines);scores=List.copyOf(scores); }
    }
    private static Main instance;
    private final Map<Player,State> players=new HashMap<>();
    private int limit;
    private Layout.Settings settings;
    private Map<String,String> aliases=Map.of();
    private boolean warnedColor;
    private AnnouncementService announcements;
    public static String listAnnouncements(String kind){return service().announcements.list(kind);}
    public static void importLegacyAnnouncements(String json)throws Exception{service().announcements.importLegacy(json);}
    public static void enableAnnouncementsAfterRestart(){service().announcements.requestEnable();}
    public static boolean ownsAnnouncements(){return ready()&&instance.announcements.publishing();}
    public static int changeAnnouncement(String kind,String action,int id,String text)throws Exception{return service().announcements.change(kind,action,id,text);}
    private static final class State {
        final Layout layout;final Channel<Sidebar> sidebars;
        Long bossId;DummyBossBar boss;Scoreboard board;Sidebar shown;
        String shownText;float shownFill=-1;BarColor shownColor;
        State(int limit,Layout.Settings settings){layout=new Layout(settings);sidebars=new Channel<>(limit);}
    }
    public void onEnable(){
        saveDefaultConfig();
        try{announcements=new AnnouncementService(this);}catch(Exception error){throw new IllegalStateException("公告儲存無法開啟，請保留原始資料",error);}
        int[] counts=announcements.counts();
        getLogger().info("§b載入 MOTD 共 §a"+counts[0]+" §b個");
        getLogger().info("§b載入廣播共 §a"+counts[1]+" §b個");
        limit=Math.max(1,Math.min(128,getConfig().getInt("max-sources",32)));
        aliases=new HashMap<>();
        var section=getConfig().getSection("colors");
        for(String key:section.getKeys(false))aliases.put(key.toLowerCase(Locale.ROOT),String.valueOf(section.get(key)));
        BarColor fallback=BarColor.WHITE;
        try{fallback=BarColor.parse(getConfig().getString("default-color","white"),aliases,BarColor.WHITE);}
        catch(IllegalArgumentException error){getLogger().warning("default-color 不是可用的顏色，改用白色");}
        settings=new Layout.Settings(
                Math.max(1,Math.min(3600,getConfig().getInt("messages.flash-seconds",15))),
                Math.max(1,Math.min(16,getConfig().getInt("messages.max-flash-lines",4))),
                Math.max(1,Math.min(32,getConfig().getInt("messages.max-pinned",8))),
                8192,fallback,Math.max(0,Math.min(2592000,getConfig().getInt("messages.pinned-max-seconds",0)))*Layout.SECOND,
                getConfig().getString("messages.flash-prefix","§f- "),getConfig().getString("messages.pinned-prefix","§f= "),
                getConfig().getBoolean("messages.trim-blank-lines",true));
        getServer().getPluginManager().registerEvents(this,this);instance=this;
        getServer().getScheduler().scheduleRepeatingTask(this,this::render,Math.max(1,getConfig().getInt("update-ticks",5)));
    }
    public static boolean ready(){return instance!=null&&instance.isEnabled();}
    private static Main service(){
        if(!ready())throw new IllegalStateException("tokaDisplay unavailable");
        if(!instance.getServer().isPrimaryThread())throw new IllegalStateException("Display API requires server thread");
        return instance;
    }
    private State state(Player player){
        Objects.requireNonNull(player);
        return players.computeIfAbsent(player,p->new State(limit,settings));
    }
    private static long ticks(int ttlTicks){if(ttlTicks<1||ttlTicks>72000)throw new IllegalArgumentException("ttlTicks 1..72000");return ttlTicks*50_000_000L;}
    /** @return 0 for "use the default" */
    private static long seconds(int seconds){if(seconds<0||seconds>86400)throw new IllegalArgumentException("seconds 0..86400");return seconds*Layout.SECOND;}
    private BarColor color(String name){
        try{return BarColor.parse(name,aliases,settings.defaultColor());}
        catch(IllegalArgumentException error){
            if(!warnedColor){warnedColor=true;getLogger().warning("不認得的血條顏色「"+name+"」，改用預設顏色；可用的顏色與別名請看 config.yml");}
            return settings.defaultColor();
        }
    }
    /** A negative fill means that the message leaves the bar alone. */
    private BarEffect effect(float fill,String color){
        if(Float.isNaN(fill)||fill<0)return null;
        return new BarEffect(fill,color(color));
    }

    /**
     * Sets the base information on the first line and how full the bar is. Only the owner of the base calls this.
     *
     * @param color   a color name or an alias, blank for the default
     * @param ttlTicks how long the base stays when it is not sent again
     */
    public static void baseBar(Player player,String text,float fill,String color,int ttlTicks){
        Main main=service();Objects.requireNonNull(text);
        main.state(player).layout.base(text,new BarEffect(Float.isFinite(fill)?fill:100,main.color(color)),ticks(ttlTicks),System.nanoTime());
    }
    /**
     * Sends a message once, under the other messages.
     *
     * @param seconds how long it stays, 0 for the default
     * @param fill    how full the bar becomes while the message shows, negative to leave the bar alone
     * @param color   the color of the bar, blank for the default
     */
    public static void flash(Player player,String text,int seconds,float fill,String color){
        Main main=service();
        main.state(player).layout.flash(text,main.effect(fill,color),seconds(seconds),System.nanoTime());
    }
    /**
     * Pins a message. Sending it again with the same id changes it and keeps its place.
     *
     * @param seconds how long it stays, 0 until it is removed
     * @param priority the higher goes first; the same goes for which message decides how the bar looks
     */
    public static void pin(Player player,String id,String text,int seconds,int priority,float fill,String color){
        Main main=service();
        if(id==null||id.isBlank()||id.length()>128)throw new IllegalArgumentException("id");
        main.state(player).layout.pin(id,text,priority,main.effect(fill,color),seconds(seconds),System.nanoTime());
    }
    public static boolean unpin(Player player,String id){
        Main main=service();State state=main.players.get(player);
        return state!=null&&state.layout.unpin(id);
    }
    /**
     * The first way to use the boss bar, kept so that the plugins that call it keep working: the base source sets the
     * base information, any other source becomes a pinned message that goes away after the time to live.
     */
    public static void bossBar(Player player,String source,String text,float progress,int priority,int ttlTicks){
        Main main=service();Objects.requireNonNull(player);Objects.requireNonNull(text);
        if(!Float.isFinite(progress)||text.length()>8192)throw new IllegalArgumentException("Invalid boss bar");
        long lifetime=ticks(ttlTicks);long now=System.nanoTime();
        if(source==null||source.isBlank()||source.length()>128)throw new IllegalArgumentException("source");
        var effect=new BarEffect(progress,main.settings.defaultColor());
        if(BASE_SOURCE.equals(source))main.state(player).layout.base(text,effect,lifetime,now);
        else main.state(player).layout.pin(source,text,priority,effect,lifetime,now);
    }
    public static void sidebar(Player player,String source,String title,String[] lines,int[] scores,int priority,int ttlTicks){
        Main main=service();Objects.requireNonNull(player);Objects.requireNonNull(title);
        if(lines.length!=scores.length||lines.length>15||title.length()>512)throw new IllegalArgumentException("Invalid sidebar");
        var values=new ArrayList<Integer>();for(int score:scores)values.add(score);
        var texts=List.of(lines.clone());if(new HashSet<>(texts).size()!=texts.size()||texts.stream().anyMatch(s->s.length()>2048))throw new IllegalArgumentException("Sidebar lines must be unique and bounded");
        main.state(player).sidebars.put(source,priority,System.nanoTime()+ticks(ttlTicks),new Sidebar(title,texts,values));
    }
    public static void remove(Player player,String source){Main main=service();State state=main.players.get(player);if(state!=null){state.layout.unpin(source);state.sidebars.remove(source);}}
    public static void removeSource(String source){Main main=service();for(State state:main.players.values()){state.layout.unpin(source);state.sidebars.remove(source);}}
    private void render(){
        announcements.pulse();
        long now=System.nanoTime();
        for(var entry:new ArrayList<>(players.entrySet())){
            Player player=entry.getKey();State state=entry.getValue();
            if(!player.isOnline()){clear(player,state);players.remove(player);continue;}
            try {
                boolean show=getConfig().getBoolean("bossbar.enabled",true)&&!state.layout.isEmpty(now);
                if(!show){if(state.bossId!=null)player.removeBossBar(state.bossId);state.bossId=null;state.boss=null;state.shownText=null;}
                else {
                    Layout.Frame frame=state.layout.frame(now);
                    BossBarColor color=BossBarColor.valueOf(frame.bar().color().name());
                    if(state.bossId==null||!player.getDummyBossBars().containsKey(state.bossId)){
                        state.boss=new DummyBossBar.Builder(player).text(frame.text()).length(frame.bar().fill()).color(color).build();state.bossId=player.createBossBar(state.boss);
                        state.shownText=frame.text();state.shownFill=frame.bar().fill();state.shownColor=frame.bar().color();
                    }else{
                        if(!frame.text().equals(state.shownText)){state.boss.setText(frame.text());state.shownText=frame.text();}
                        if(frame.bar().fill()!=state.shownFill){state.boss.setLength(frame.bar().fill());state.shownFill=frame.bar().fill();}
                        if(frame.bar().color()!=state.shownColor){state.boss.setColor(color);state.shownColor=frame.bar().color();}
                    }
                }
                Sidebar sidebar=state.sidebars.winner(now).orElse(null);
                if(!getConfig().getBoolean("sidebar.enabled",true))sidebar=null;
                if(!Objects.equals(sidebar,state.shown)){
                    if(sidebar==null){if(state.board!=null)state.board.hideFor(player);state.board=null;state.shown=null;}
                    else {
                        boolean fresh=state.board==null||state.shown==null||!state.shown.title().equals(sidebar.title());
                        if(fresh){if(state.board!=null)state.board.hideFor(player);state.board=new Scoreboard(sidebar.title(),Scoreboard.SortOrder.ASCENDING,Scoreboard.DisplaySlot.SIDEBAR);}
                        state.board.holdUpdates();
                        for(String old:new ArrayList<>(state.board.getScores().keySet()))if(!sidebar.lines().contains(old))state.board.removeScore(old);
                        for(int i=0;i<sidebar.lines().size();i++)state.board.setScore(sidebar.lines().get(i),sidebar.scores().get(i));
                        state.board.unholdUpdates();if(fresh)state.board.showTo(player);state.shown=sidebar;
                    }
                }
                if(state.layout.isEmpty(now)&&state.sidebars.empty()){clear(player,state);players.remove(player);}
            }catch(RuntimeException|LinkageError error){clear(player,state);players.remove(player);getLogger().warning("玩家顯示暫停："+error.getClass().getSimpleName());}
        }
    }
    private void clear(Player player,State state){try{if(state.bossId!=null)player.removeBossBar(state.bossId);}catch(RuntimeException|LinkageError ignored){}try{if(state.board!=null)state.board.hideFor(player);}catch(RuntimeException|LinkageError ignored){}}
    @EventHandler public void quit(PlayerQuitEvent event){State state=players.remove(event.getPlayer());if(state!=null)clear(event.getPlayer(),state);}
    public void onDisable(){if(announcements!=null)try{announcements.close();}catch(Exception error){getLogger().warning("公告儲存關閉失敗");}instance=null;for(var entry:players.entrySet())clear(entry.getKey(),entry.getValue());players.clear();}
    /**
     * The pinned messages and the messages sent once of a player, in the order they are shown, as a JSON array of
     * {position, kind, id, priority, seconds, text}. The base information is not listed.
     * seconds is -1 for a message that stays until it is removed.
     */
    public static String listMessages(Player player){
        Main main=service();State state=main.players.get(Objects.requireNonNull(player));
        var rows=new com.google.gson.JsonArray();
        if(state!=null)for(Layout.Entry entry:state.layout.entries(System.nanoTime())){
            var row=new com.google.gson.JsonObject();
            row.addProperty("position",entry.position());row.addProperty("kind",entry.kind());row.addProperty("id",entry.id());
            row.addProperty("priority",entry.priority());row.addProperty("seconds",entry.seconds());row.addProperty("text",entry.text());
            rows.add(row);
        }
        return rows.toString();
    }
    /**
     * Removes one message, by its id or by its position in the list. The base information cannot be removed.
     *
     * @return true when a message was removed
     */
    public static boolean removeMessage(Player player,String idOrPosition){
        Main main=service();State state=main.players.get(Objects.requireNonNull(player));
        return state!=null&&state.layout.remove(Objects.requireNonNull(idOrPosition),System.nanoTime());
    }
    /** Removes every pinned message and every message sent once of a player. The base information stays. */
    public static void clearMessages(Player player){
        Main main=service();State state=main.players.get(Objects.requireNonNull(player));
        if(state!=null)state.layout.clearMessages();
    }
    /** The commands only use the methods above, so that what an admin can do is what a plugin can do. */
    private List<Player> targets(CommandSender sender,String name){
        if("*".equals(name))return new ArrayList<>(players.keySet());
        Player player=getServer().getPlayer(name);
        if(player==null){sender.sendMessage("找不到玩家 "+name+"（需要完整名稱，且要在線上）");return List.of();}
        return List.of(player);
    }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&announcements.command(sender,args))return true;
        if(!sender.hasPermission("tokaDisplay.admin"))return false;
        String sub=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "testbar"->{
                if(!(sender instanceof Player self)){sender.sendMessage("只能在遊戲內使用");return true;}
                String color=args.length>=2?args[1]:null;float fill=50f;
                try{if(args.length>=3)fill=Float.parseFloat(args[2]);}catch(NumberFormatException error){sender.sendMessage("填充度要是 0 到 100 的數字");return true;}
                pin(self,"display:testbar","§e血條測試："+(color==null?"預設顏色":color)+" "+(int)fill+"%",10,99,fill,color);
                sender.sendMessage("已用 10 秒顯示測試血條，用法：/display testbar <顏色> [填充度]");
            }
            case "list"->{
                List<Player> list=args.length>=2?targets(sender,args[1]):new ArrayList<>(players.keySet());
                int total=0;
                for(Player player:list){
                    for(var row:com.google.gson.JsonParser.parseString(listMessages(player)).getAsJsonArray()){
                        var entry=row.getAsJsonObject();total++;
                        String text=entry.get("text").getAsString().replace('\n',' ');if(text.length()>30)text=text.substring(0,30)+"…";
                        long seconds=entry.get("seconds").getAsLong();
                        sender.sendMessage(player.getName()+" #"+entry.get("position").getAsInt()+" "+(entry.get("kind").getAsString().equals("pinned")?"固定":"單次")+" id="+entry.get("id").getAsString()+" 優先權="+entry.get("priority").getAsInt()+" "+(seconds<0?"永久":"剩 "+seconds+" 秒")+" "+text);
                    }
                }
                sender.sendMessage("共 "+total+" 則訊息（基本資訊那一行不列出）");
            }
            case "unpin"->{
                if(args.length!=3){sender.sendMessage("用法：/display unpin <玩家或*> <訊息id或編號>");return true;}
                int removed=0;for(Player player:targets(sender,args[1]))if(removeMessage(player,args[2]))removed++;
                sender.sendMessage("已從 "+removed+" 位玩家移除訊息 "+args[2]);
            }
            case "clear"->{
                if(args.length!=2){sender.sendMessage("用法：/display clear <玩家或*>");return true;}
                int cleared=0;for(Player player:targets(sender,args[1])){clearMessages(player);cleared++;}
                sender.sendMessage("已清除 "+cleared+" 位玩家的固定與單次訊息，基本資訊不受影響");
            }
            default->sender.sendMessage("tokaDisplay：就緒；顯示中的玩家 "+players.size()+"\n/display list [玩家] | unpin <玩家或*> <訊息id或編號> | clear <玩家或*>");
        }
        return true;
    }
}
