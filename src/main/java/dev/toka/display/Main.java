package dev.toka.display;

import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.DummyBossBar;
import cn.nukkit.scoreboard.Scoreboard;
import java.util.*;

/** Owns only its own bars/boards, never clears another plugin's display objects. */
public final class Main extends PluginBase implements Listener {
    public record Boss(String text,float progress) {}
    public record Sidebar(String title,List<String> lines,List<Integer> scores) {
        public Sidebar { lines=List.copyOf(lines);scores=List.copyOf(scores); }
    }
    private static Main instance;
    private final Map<Player,State> players=new HashMap<>();
    private int limit;
    private static final class State {
        final Channel<Boss> bosses;final Channel<Sidebar> sidebars;
        Long bossId;DummyBossBar boss;Scoreboard board;Sidebar shown;
        State(int limit){bosses=new Channel<>(limit);sidebars=new Channel<>(limit);}
    }
    public void onEnable(){
        saveDefaultConfig();limit=Math.max(1,Math.min(128,getConfig().getInt("max-sources",32)));
        getServer().getPluginManager().registerEvents(this,this);instance=this;
        getServer().getScheduler().scheduleRepeatingTask(this,this::render,Math.max(1,getConfig().getInt("update-ticks",5)));
    }
    public static boolean ready(){return instance!=null&&instance.isEnabled();}
    private static Main service(){
        if(!ready())throw new IllegalStateException("tokaDisplay unavailable");
        if(!instance.getServer().isPrimaryThread())throw new IllegalStateException("Display API requires server thread");
        return instance;
    }
    private static long deadline(int ttlTicks){if(ttlTicks<1||ttlTicks>72000)throw new IllegalArgumentException("ttlTicks 1..72000");return System.nanoTime()+ttlTicks*50_000_000L;}
    public static void bossBar(Player player,String source,String text,float progress,int priority,int ttlTicks){
        Main main=service();Objects.requireNonNull(player);Objects.requireNonNull(text);
        if(!Float.isFinite(progress)||text.length()>8192)throw new IllegalArgumentException("Invalid boss bar");
        main.players.computeIfAbsent(player,p->new State(main.limit)).bosses.put(source,priority,deadline(ttlTicks),new Boss(text,Math.max(0,Math.min(100,progress))));
    }
    public static void sidebar(Player player,String source,String title,String[] lines,int[] scores,int priority,int ttlTicks){
        Main main=service();Objects.requireNonNull(player);Objects.requireNonNull(title);
        if(lines.length!=scores.length||lines.length>15||title.length()>512)throw new IllegalArgumentException("Invalid sidebar");
        var values=new ArrayList<Integer>();for(int score:scores)values.add(score);
        var texts=List.of(lines.clone());if(new HashSet<>(texts).size()!=texts.size()||texts.stream().anyMatch(s->s.length()>2048))throw new IllegalArgumentException("Sidebar lines must be unique and bounded");
        main.players.computeIfAbsent(player,p->new State(main.limit)).sidebars.put(source,priority,deadline(ttlTicks),new Sidebar(title,texts,values));
    }
    public static void remove(Player player,String source){Main main=service();State state=main.players.get(player);if(state!=null){state.bosses.remove(source);state.sidebars.remove(source);}}
    public static void removeSource(String source){Main main=service();for(State state:main.players.values()){state.bosses.remove(source);state.sidebars.remove(source);}}
    private void render(){
        long now=System.nanoTime();
        for(var entry:new ArrayList<>(players.entrySet())){
            Player player=entry.getKey();State state=entry.getValue();
            if(!player.isOnline()){clear(player,state);players.remove(player);continue;}
            try {
                Boss boss=state.bosses.winner(now).orElse(null);
                if(boss==null){if(state.bossId!=null)player.removeBossBar(state.bossId);state.bossId=null;state.boss=null;}
                else {
                    if(state.bossId==null||!player.getDummyBossBars().containsKey(state.bossId)){
                        state.boss=new DummyBossBar.Builder(player).text(boss.text()).length(boss.progress()).build();state.bossId=player.createBossBar(state.boss);
                    }else{state.boss.setText(boss.text());state.boss.setLength(boss.progress());}
                }
                Sidebar sidebar=state.sidebars.winner(now).orElse(null);
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
                if(state.bosses.empty()&&state.sidebars.empty()){clear(player,state);players.remove(player);}
            }catch(RuntimeException|LinkageError error){clear(player,state);players.remove(player);getLogger().warning("玩家顯示暫停："+error.getClass().getSimpleName());}
        }
    }
    private void clear(Player player,State state){try{if(state.bossId!=null)player.removeBossBar(state.bossId);}catch(RuntimeException|LinkageError ignored){}try{if(state.board!=null)state.board.hideFor(player);}catch(RuntimeException|LinkageError ignored){}}
    @EventHandler public void quit(PlayerQuitEvent event){State state=players.remove(event.getPlayer());if(state!=null)clear(event.getPlayer(),state);}
    public void onDisable(){instance=null;for(var entry:players.entrySet())clear(entry.getKey(),entry.getValue());players.clear();}
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!sender.hasPermission("tokaDisplay.admin"))return false;sender.sendMessage("tokaDisplay：就緒；顯示中的玩家 "+players.size());return true;}
}

