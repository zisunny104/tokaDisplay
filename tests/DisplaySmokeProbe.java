import cn.nukkit.plugin.PluginBase;
public class DisplaySmokeProbe extends PluginBase {
 public void onEnable(){getServer().getScheduler().scheduleDelayedTask(this,()->{
  try{
   var zero=(prj.toka.zero.Main)getServer().getPluginManager().getPlugin("zero");
   boolean expected=Boolean.getBoolean("probe.display");
   if(zero==null||!zero.isEnabled()||!zero.isModuleActive("auto-inventory"))throw new AssertionError("zero base modules");
   if(prj.toka.zero.runtime.DisplayBridge.active()!=expected)throw new AssertionError("Display readiness");
   if(expected){
    var plugin=getServer().getPluginManager().getPlugin("tokaDisplay");
    plugin.getClass().getMethod("bossBar",cn.nukkit.Player.class,String.class,String.class,float.class,int.class,int.class);
    plugin.getClass().getMethod("sidebar",cn.nukkit.Player.class,String.class,String.class,String[].class,int[].class,int.class,int.class);
    prj.toka.zero.runtime.DisplayBridge.shutdown();
   }
   getLogger().info("DISPLAY_SMOKE_PASS display="+expected);
  }catch(Throwable e){getLogger().error("DISPLAY_SMOKE_FAIL",e);}finally{getServer().shutdown();}
 },5);}
}
