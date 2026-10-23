package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.player.PlayerLocallyInitializedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerRespawnEvent;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.utils.DummyBossBar;
import cn.nukkit.scoreboard.Scoreboard;
import dev.toka.i18n.Messages;
import java.util.*;
import java.util.function.Function;

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
    private record Section(String owner,Function<Player,String> line) {}
    private final Map<String,Section> sections=new LinkedHashMap<>();
    private final Set<String> failedSections=new HashSet<>();
    private record TagPart(String owner,java.util.function.BiFunction<UUID,Player,String> render) {}
    private final Map<String,TagPart> tagParts=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> failedTagParts=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private NameTag nameTag=new NameTag("${name}");
    private final Map<Integer,String> modeColors=new HashMap<>();
    private String fallbackModeColor="§7";
    private boolean warnedOverflow;
    private List<String> sidebarLayout=List.of();
    private String sidebarTitle="";
    private AnnouncementService announcements;
    private Messages messages;
    /** The texts that the plugin shows, shared with the announcements. */
    Messages messages(){return messages;}
    public static String listAnnouncements(String kind){return service().announcements.list(kind);}
    // TODO: Writes on the calling thread. Move to a callback form (result or error delivered on the main thread) in the next API version.
    public static void replaceAnnouncements(String json)throws Exception{service().announcements.replace(json);}
    public static String announcementsSnapshot(){return service().announcements.snapshot();}
    public static boolean verifyAnnouncements(String json){return service().announcements.verify(json);}
    public static void setAnnouncementsEnabled(boolean enabled){service().announcements.setEnabled(enabled);}
    public static boolean ownsAnnouncements(){return ready()&&instance.announcements.publishing();}
    // TODO: Writes on the calling thread. Move to a callback form (result or error delivered on the main thread) in the next API version.
    public static int changeAnnouncement(String kind,String action,int id,String text)throws Exception{return service().announcements.change(kind,action,id,text);}
    private static final class State {
        final Layout layout;final Channel<Sidebar> sidebars;
        Long bossId;DummyBossBar boss;Scoreboard board;Sidebar shown,composed;
        String shownText;float shownFill=-1;BarColor shownColor;long colorAt,reshownAt;
        State(int limit,Layout.Settings settings){layout=new Layout(settings);sidebars=new Channel<>(limit);}
    }
    public void onEnable(){
        saveDefaultConfig();
        messages=new Messages(getClass().getClassLoader(),getDataFolder().toPath(),getConfig().getString("language","zh-TW"),line->getLogger().warning(line));
        suggest("display",
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"help"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"list"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"list"}), CommandParameter.newType("玩家", true, CommandParamType.TARGET)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"unpin"}), CommandParameter.newType("玩家", CommandParamType.TARGET), CommandParameter.newType("訊息ID或編號", CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"unpin"}), CommandParameter.newEnum("玩家", new String[]{"*"}), CommandParameter.newType("訊息ID或編號", CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"clear"}), CommandParameter.newType("玩家", CommandParamType.TARGET)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"clear"}), CommandParameter.newEnum("玩家", new String[]{"*"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"testbar"}), CommandParameter.newEnum("顏色", Arrays.stream(BarColor.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toArray(String[]::new)), CommandParameter.newType("填充度", true, CommandParamType.FLOAT)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"motd", "broadcast"}), CommandParameter.newEnum("項目", new String[]{"list"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"motd", "broadcast"}), CommandParameter.newEnum("項目", new String[]{"add"}), CommandParameter.newType("文字", false, CommandParamType.RAWTEXT)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"motd", "broadcast"}), CommandParameter.newEnum("項目", new String[]{"set"}), CommandParameter.newType("ID", CommandParamType.INT), CommandParameter.newType("文字", false, CommandParamType.RAWTEXT)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"motd", "broadcast"}), CommandParameter.newEnum("項目", new String[]{"remove"}), CommandParameter.newType("ID", CommandParamType.INT)});
        try{announcements=new AnnouncementService(this);}catch(Exception error){throw new IllegalStateException("Cannot open the announcement storage, keep the original data",error);}
        int[] counts=announcements.counts();
        getLogger().info(messages.text("log.motdLoaded",counts[0]));
        getLogger().info(messages.text("log.broadcastLoaded",counts[1]));
        limit=Math.max(1,Math.min(128,getConfig().getInt("max-sources",32)));
        aliases=new HashMap<>();
        var section=getConfig().getSection("colors");
        for(String key:section.getKeys(false))aliases.put(key.toLowerCase(Locale.ROOT),String.valueOf(section.get(key)));
        BarColor fallback=BarColor.WHITE;
        try{fallback=BarColor.parse(getConfig().getString("default-color","white"),aliases,BarColor.WHITE);}
        catch(IllegalArgumentException error){getLogger().warning(messages.text("log.badDefaultColor"));}
        settings=new Layout.Settings(
                Math.max(1,Math.min(3600,getConfig().getInt("messages.flash-seconds",15))),
                Math.max(1,Math.min(16,getConfig().getInt("messages.max-flash-lines",4))),
                Math.max(1,Math.min(32,getConfig().getInt("messages.max-pinned",8))),
                8192,fallback,Math.max(0,Math.min(2592000,getConfig().getInt("messages.pinned-max-seconds",0)))*Layout.SECOND,
                getConfig().getString("messages.flash-prefix","§8- "),getConfig().getString("messages.pinned-prefix","§8= "),
                getConfig().getBoolean("messages.trim-blank-lines",true),
                Math.max(0,Math.min(3,getConfig().getInt("messages.bar-gap-lines",1))),
                Math.max(0,Math.min(200,getConfig().getInt("messages.status-min-width",60))));
        getServer().getPluginManager().registerEvents(this,this);instance=this;
        sidebarTitle=getConfig().getString("sidebar.title","§e                              §r");
        sidebarLayout=getConfig().exists("sidebar.layout")?getConfig().getStringList("sidebar.layout"):DEFAULT_LAYOUT;
        nameTag=new NameTag(getConfig().getString("nametag.format","${prefix}§r${affiliation}§r${mode}[${title}${mode}${name}${mode}]"));
        fallbackModeColor=getConfig().getString("nametag.fallback-mode-color","§7");
        modeColors.put(0,getConfig().getString("nametag.mode-colors.survival","§a"));
        modeColors.put(1,getConfig().getString("nametag.mode-colors.creative","§b"));
        modeColors.put(2,getConfig().getString("nametag.mode-colors.adventure","§6"));
        modeColors.put(3,getConfig().getString("nametag.mode-colors.spectator","§e"));
        registerBuiltins();
        getServer().getScheduler().scheduleRepeatingTask(this,this::render,Math.max(1,getConfig().getInt("update-ticks",5)));
        getServer().getScheduler().scheduleRepeatingTask(this,this::compose,Math.max(1,getConfig().getInt("sidebar.refresh-ticks",20)));
    }

    private void suggest(String name, CommandParameter[]... forms) {
        Command registered = (Command) getCommand(name);
        if (registered == null) return;
        registered.getCommandParameters().clear();
        for (int i = 0; i < forms.length; i++) registered.addCommandParameters("choice" + i, forms[i]);
    }
    /** Same as the default in config.yml; it only protects old configs that have no sidebar.layout. */
    private static final List<String> DEFAULT_LAYOUT=List.of("region","position","bearing","-","money","coin","-","item","-","tps","ping","area");
    /**
     * Registers a sidebar section: one line that sidebar.layout in config.yml places. Registering an id again replaces it.
     *
     * @param line the text for this player, null or empty when the line is not shown now
     */
    public static void registerSection(String id,Function<Player,String> line){registerSection("",id,line);}
    /** Same, with an owner name so that the plugin can remove all of its sections with {@link #unregisterSections}. */
    public static void registerSection(String owner,String id,Function<Player,String> line){
        Main main=service();Objects.requireNonNull(owner);Objects.requireNonNull(line);
        if(id==null||id.isBlank()||id.length()>64||SidebarCompose.SEPARATOR.equals(id))throw new IllegalArgumentException("id");
        main.sections.put(id,new Section(owner,line));main.failedSections.remove(id);
    }
    public static void unregisterSection(String id){service().sections.remove(id);}
    /** Removes every section registered with this owner name; call it when the plugin is disabled. */
    public static void unregisterSections(String owner){Objects.requireNonNull(owner);service().sections.values().removeIf(s->s.owner().equals(owner));}
    /**
     * Registers one part of the name tag: the text that replaces <code>${id}</code> in nametag.format of config.yml.
     * The part gets the player's id and the player when online (null when offline), so a tag can also be built for someone
     * who has left. A part that throws is skipped and reported once; it never breaks the name tag.
     *
     * @param render the text for this player, null or empty for nothing
     */
    public static void registerNameTagPart(String owner,String id,java.util.function.BiFunction<UUID,Player,String> render){
        Main main=service();Objects.requireNonNull(owner);Objects.requireNonNull(render);
        if(id==null||id.isBlank()||id.length()>64||id.equals("name")||id.equals("mode"))throw new IllegalArgumentException("id");
        main.tagParts.put(id,new TagPart(owner,render));main.failedTagParts.remove(id);
    }
    /** Removes every name tag part registered with this owner name; call it when the plugin is disabled. */
    public static void unregisterNameTagParts(String owner){Objects.requireNonNull(owner);service().tagParts.values().removeIf(p->p.owner().equals(owner));}
    /**
     * The name tag for a player, online or not. Pass the online player when there is one so the game mode colour is right;
     * for someone offline the fallback colour is used.
     */
    public static String nameTag(UUID id,String name,Player online){return service().composeNameTag(id,name,online);}
    private String composeNameTag(UUID id,String name,Player online){
        return nameTag.compose(key->{
            if(key.equals("name"))return name;
            if(key.equals("mode"))return online==null?fallbackModeColor:modeColors.getOrDefault(online.getGamemode(),fallbackModeColor);
            TagPart part=tagParts.get(key);
            if(part==null)return null;
            try{return part.render().apply(id,online);}
            catch(RuntimeException|LinkageError error){
                if(failedTagParts.add(key))getLogger().warning(messages.text("log.tagPartFailed",key));
                return null;
            }
        });
    }
    private void registerBuiltins(){
        String own=getName();
        registerSection(own,"position",p->messages.text("ui.sidebar.position",(int)p.getX(),(int)p.getY(),(int)p.getZ()));
        registerSection(own,"bearing",p->messages.text("ui.sidebar.bearing",messages.lines("ui.sidebar.directions").get((int)((((p.getYaw()%360)+360)%360+22.5)/45)%8)));
        registerSection(own,"item",p->{var item=p.getInventory().getItemInHand();return messages.text("ui.sidebar.item",item.getId(),item.getDamage());});
        registerSection(own,"tps",p->{int tps=(int)getServer().getTicksPerSecond();return messages.text("ui.sidebar.tps",tps==20?"§a":"§e",tps);});
        registerSection(own,"ping",p->{int ping=p.getPing();return messages.text("ui.sidebar.ping",ping<=75?"§a":ping<=100?"§e":ping<150?"§c":"§7",ping);});
    }
    /** Composes the layout for every player whose client is ready; render shows it when no plugin sent a sidebar. */
    private void compose(){
        boolean enabled=getConfig().getBoolean("sidebar.enabled",true);
        for(Player player:new ArrayList<>(getServer().getOnlinePlayers().values())){
            if(!player.isOnline()||!player.spawned||!player.locallyInitialized)continue;
            try{
                Sidebar next=null;
                if(enabled){
                    var result=SidebarCompose.compose(sidebarLayout,id->{Section section=sections.get(id);return section==null?null:section.line().apply(player);},15);
                    for(String id:result.failed())if(failedSections.add(id))getLogger().warning(messages.text("log.sectionFailed",id));
                    if(result.truncated()&&!warnedOverflow){warnedOverflow=true;getLogger().warning(messages.text("log.sidebarTooLong"));}
                    if(!result.lines().isEmpty())next=new Sidebar(sidebarTitle,result.lines(),result.scores());
                }
                State state=next==null?players.get(player):state(player);
                if(state!=null)state.composed=next;
            }catch(RuntimeException|LinkageError error){getLogger().warning(messages.text("log.playerPaused",error.getClass().getSimpleName()));}
        }
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
    private String colorNames(){
        java.util.TreeSet<String> names=new java.util.TreeSet<>(aliases.keySet());
        for(BarColor c:BarColor.values())names.add(c.name().toLowerCase(Locale.ROOT));
        return String.join(", ",names);
    }
    private BarColor color(String name){
        try{return BarColor.parse(name,aliases,settings.defaultColor());}
        catch(IllegalArgumentException error){
            if(!warnedColor){warnedColor=true;getLogger().warning(messages.text("log.badBarColor",name,colorNames()));}
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
     * The older boss bar entry point: the base source sets the base information, any other source becomes a pinned
     * message that goes away after the time to live.
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
    /** The core only knows seven colors and numbers white wrong for the client, so the packet is made here. */
    private static void sendColor(Player player,DummyBossBar bar,BarColor color){
        var packet=new cn.nukkit.network.protocol.BossEventPacket();
        packet.bossEid=bar.getBossBarId();packet.type=cn.nukkit.network.protocol.BossEventPacket.TYPE_TEXTURE;
        packet.color=color.clientIndex();packet.overlay=0;
        player.dataPacket(packet);
    }
    private void render(){
        // An announcement failure must not stop the boss bars of every player
        try{announcements.pulse();}catch(RuntimeException|LinkageError error){getLogger().warning(messages.text("log.announceFailed",error.getClass().getSimpleName()));}
        long now=System.nanoTime();
        boolean bossEnabled=getConfig().getBoolean("bossbar.enabled",true),sidebarEnabled=getConfig().getBoolean("sidebar.enabled",true);
        for(var entry:new ArrayList<>(players.entrySet())){
            Player player=entry.getKey();State state=entry.getValue();
            if(!player.isOnline()){clear(player,state);players.remove(player);continue;}
            // The client drops what it gets before it has finished joining, and nothing would send it again
            if(!player.spawned||!player.locallyInitialized)continue;
            try {
                boolean show=bossEnabled&&!state.layout.isEmpty(now);
                if(!show){if(state.bossId!=null)player.removeBossBar(state.bossId);state.bossId=null;state.boss=null;state.shownText=null;}
                else {
                    Layout.Frame frame=state.layout.frame(now);
                    if(state.bossId==null||!player.getDummyBossBars().containsKey(state.bossId)){
                        state.boss=new DummyBossBar.Builder(player).text(frame.text()).length(frame.bar().fill()).build();state.bossId=player.createBossBar(state.boss);
                        state.shownText=frame.text();state.shownFill=frame.bar().fill();state.shownColor=null;
                    }else{
                        if(!frame.text().equals(state.shownText)){state.boss.setText(frame.text());state.shownText=frame.text();}
                        if(frame.bar().fill()!=state.shownFill){state.boss.setLength(frame.bar().fill());state.shownFill=frame.bar().fill();}
                    }
                    if(now-state.reshownAt>5_000_000_000L){if(state.reshownAt!=0)state.boss.reshow();state.reshownAt=now;}
                    // The color is sent when it changes and now and then again, because the game forgets it when the bar is shown again
                    if(frame.bar().color()!=state.shownColor||now-state.colorAt>2_000_000_000L){sendColor(player,state.boss,frame.bar().color());state.shownColor=frame.bar().color();state.colorAt=now;}
                }
                // A sidebar sent by a plugin goes before the composed one
                Sidebar sidebar=state.sidebars.winner(now).orElse(state.composed);
                if(!sidebarEnabled)sidebar=null;
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
                if(state.layout.isEmpty(now)&&state.sidebars.empty()&&state.composed==null){clear(player,state);players.remove(player);}
            }catch(RuntimeException|LinkageError error){clear(player,state);players.remove(player);getLogger().warning(messages.text("log.playerPaused",error.getClass().getSimpleName()));}
        }
    }
    private void clear(Player player,State state){try{if(state.bossId!=null)player.removeBossBar(state.bossId);}catch(RuntimeException|LinkageError ignored){}try{if(state.board!=null)state.board.hideFor(player);}catch(RuntimeException|LinkageError ignored){}}
    /** Forgets what was sent, so that the next render sends the bar and the board again. */
    private void resync(Player player){
        State state=players.get(player);
        if(state==null||!player.isOnline())return;
        clear(player,state);
        state.bossId=null;state.boss=null;state.shownText=null;state.shownColor=null;state.reshownAt=0;
        state.board=null;state.shown=null;
    }
    private void resyncLater(Player player){getServer().getScheduler().scheduleDelayedTask(this,()->resync(player),10);}
    @EventHandler public void initialized(PlayerLocallyInitializedEvent event){resyncLater(event.getPlayer());}
    @EventHandler public void respawn(PlayerRespawnEvent event){resyncLater(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){State state=players.remove(event.getPlayer());if(state!=null)clear(event.getPlayer(),state);}
    public void onDisable(){if(announcements!=null)try{announcements.close();}catch(Exception error){getLogger().warning(messages.text("log.storageCloseFailed"));}if(instance==this)instance=null;for(var entry:players.entrySet())clear(entry.getKey(),entry.getValue());players.clear();}
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
        if(player==null){sender.sendMessage(messages.text("err.playerNotFound",name));return List.of();}
        return List.of(player);
    }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&announcements.command(sender,args))return true;
        if(!sender.hasPermission("tokaDisplay.admin"))return false;
        String sub=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
        switch(sub){
            case "testbar"->{
                if(!(sender instanceof Player self)){sender.sendMessage(messages.text("err.playerOnly"));return true;}
                String color=args.length>=2?args[1]:null;float fill=50f;
                try{if(args.length>=3)fill=Float.parseFloat(args[2]);if(!Float.isFinite(fill))throw new NumberFormatException();}catch(NumberFormatException error){sender.sendMessage(messages.text("err.fillRange"));return true;}
                pin(self,"display:testbar",messages.text("msg.display.testbar.text",color==null?messages.text("status.defaultColor"):color,(int)fill),10,99,fill,color);
                sender.sendMessage(messages.text("msg.display.testbar.started"));
            }
            case "list"->{
                List<Player> list=args.length>=2?targets(sender,args[1]):new ArrayList<>(players.keySet());
                int total=0;
                for(Player player:list){
                    for(var row:com.google.gson.JsonParser.parseString(listMessages(player)).getAsJsonArray()){
                        var entry=row.getAsJsonObject();total++;
                        String text=entry.get("text").getAsString().replace('\n',' ');if(text.length()>30)text=text.substring(0,30)+"\u2026";
                        long seconds=entry.get("seconds").getAsLong();
                        String kind=messages.text(entry.get("kind").getAsString().equals("pinned")?"status.pinned":"status.once");
                        String left=seconds<0?messages.text("status.forever"):messages.text("status.remaining",seconds);
                        sender.sendMessage(messages.text("msg.display.list.row",player.getName(),entry.get("position").getAsInt(),kind,entry.get("id").getAsString(),entry.get("priority").getAsInt(),left,text));
                    }
                }
                sender.sendMessage(messages.text("msg.display.list.total",total));
            }
            case "unpin"->{
                if(args.length!=3){sender.sendMessage(messages.text("cmd.display.unpin.usage"));return true;}
                int removed=0;for(Player player:targets(sender,args[1]))if(removeMessage(player,args[2]))removed++;
                sender.sendMessage(messages.text("msg.display.unpinned",removed,args[2]));
            }
            case "clear"->{
                if(args.length!=2){sender.sendMessage(messages.text("cmd.display.clear.usage"));return true;}
                int cleared=0;for(Player player:targets(sender,args[1])){clearMessages(player);cleared++;}
                sender.sendMessage(messages.text("msg.display.cleared",cleared));
            }
            case "help"->sender.sendMessage(String.join("\n",messages.lines("help.display")));
            default->sender.sendMessage(messages.text("msg.display.status",players.size())+"\n"+messages.text("cmd.display.usage"));
        }
        return true;
    }
}
