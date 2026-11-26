package com.voidsmp.voidranks;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public final class VOIDRanks extends JavaPlugin implements Listener, CommandExecutor {
  private final SecureRandom rng = new SecureRandom();
  private final Map<UUID,Integer> grades = new HashMap<>();
  private final Map<UUID,String> hashes = new HashMap<>();
  private final Map<UUID,Long> sessions = new HashMap<>(), locked = new HashMap<>();
  private final Map<UUID,Integer> failures = new HashMap<>();
  private final Map<UUID,PermissionAttachment> attachments = new HashMap<>();
  private final Map<UUID,SavedState> spectating = new HashMap<>();

  public void onEnable() {
    saveDefaultConfig(); loadData();
    for (String c : List.of("alogin","achange","agrade","makeadmin","re","vrank")) getCommand(c).setExecutor(this);
    getServer().getPluginManager().registerEvents(this,this);
    if (hashes.isEmpty() && getConfig().getString("bootstrap.hash")==null) {
      String key=randomKey(5); getConfig().set("bootstrap.hash",hash(key)); saveConfig();
      getLogger().warning("VOID RANKS INITIAL OWNER KEY (show once): "+key);
      getLogger().warning("Give this key only to the server owner; restrict console access.");
    }
    for (Player p:Bukkit.getOnlinePlayers()) apply(p);
    getLogger().info("VOID Ranks enabled without LuckPerms or Vault.");
  }
  public void onDisable() { for(Player p:Bukkit.getOnlinePlayers()) stopSpectating(p,false); saveData(); }
  private void loadData() {
    File file=new File(getDataFolder(),"data.yml");
    if(!file.exists()) return;
    var y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
    ConfigurationSection s=y.getConfigurationSection("players"); if(s==null)return;
    for(String id:s.getKeys(false)) try {
      UUID u=UUID.fromString(id); grades.put(u,y.getInt("players."+id+".grade",0));
      String h=y.getString("players."+id+".hash"); if(h!=null)hashes.put(u,h);
    } catch(Exception ignored){}
  }
  private void saveData() {
    var y=new org.bukkit.configuration.file.YamlConfiguration();
    for(UUID u:grades.keySet()){y.set("players."+u+".grade",grades.get(u)); if(hashes.containsKey(u))y.set("players."+u+".hash",hashes.get(u));}
    try{y.save(new File(getDataFolder(),"data.yml"));}catch(Exception e){getLogger().severe("Save failed: "+e.getMessage());}
  }
  private String randomKey(int n){String a="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(a.charAt(rng.nextInt(a.length())));return b.toString();}
  private String hash(String v){
    try{byte[] salt=new byte[16];rng.nextBytes(salt);PBEKeySpec spec=new PBEKeySpec(v.toCharArray(),salt,210000,256);byte[] out=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(out);}catch(Exception e){throw new IllegalStateException(e);}
  }
  private boolean verify(String v,String stored){
    try{String[] p=stored.split(":");PBEKeySpec spec=new PBEKeySpec(v.toCharArray(),Base64.getDecoder().decode(p[0]),210000,256);byte[] out=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return MessageDigest.isEqual(out,Base64.getDecoder().decode(p[1]));}catch(Exception e){return false;}
  }
  private String msg(String k){return color(getConfig().getString("messages."+k,"&c"+k));}
  private String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
  private int grade(Player p){return grades.getOrDefault(p.getUniqueId(),0);}
  private String gradeName(int g){return getConfig().getString("grades."+g+".name","Grade "+g);}
  private boolean logged(Player p){Long t=sessions.get(p.getUniqueId());return t!=null && System.currentTimeMillis()-t<getConfig().getLong("settings.session-timeout-minutes",60)*60000L;}
  private boolean session(Player p){if(!logged(p)){p.sendMessage(msg("not-logged-in"));return false;}return true;}
  private boolean allowed(Player p,int min,int max){if(!session(p))return false;if(grade(p)<min||grade(p)>max){p.sendMessage(msg("no-permission"));return false;}return true;}
  private void apply(Player p){
    PermissionAttachment old=attachments.remove(p.getUniqueId());if(old!=null)p.removeAttachment(old);
    PermissionAttachment a=p.addAttachment(this); ConfigurationSection sec=getConfig().getConfigurationSection("grades."+grade(p));
    if(sec!=null)for(String node:sec.getStringList("permissions"))if(validNode(node))a.setPermission(node,true);
    attachments.put(p.getUniqueId(),a);
    String prefix=getConfig().getString("grades."+grade(p)+".prefix","&7[Member]");
    p.setDisplayName(color(prefix+" &f"+p.getName()));p.setPlayerListName(p.getDisplayName());
  }
  private boolean validNode(String n){return n!=null&&!n.isBlank()&&!n.equals("*")&&!n.endsWith(".*");}
  @EventHandler public void join(PlayerJoinEvent e){apply(e.getPlayer());}
  @EventHandler public void quit(PlayerQuitEvent e){Player p=e.getPlayer();stopSpectating(p,false);sessions.remove(p.getUniqueId());PermissionAttachment a=attachments.remove(p.getUniqueId());if(a!=null)p.removeAttachment(a);}
  private boolean execute(CommandSender sender,Command cmd,String[] a){
    String c=cmd.getName().toLowerCase(Locale.ROOT);
    if(c.equals("vrank"))return rankCommand(sender,a);
    if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}
    UUID id=p.getUniqueId();
    switch(c){
      case "alogin" -> {
        if(a.length!=1){p.sendMessage(color("&cUsage: /alogin <key>"));return true;}
        if(locked.getOrDefault(id,0L)>System.currentTimeMillis()){p.sendMessage(color("&cLogin locked temporarily."));return true;}
        String stored=hashes.get(id);boolean ok=stored!=null&&verify(a[0],stored);
        String boot=getConfig().getString("bootstrap.hash");
        if(!ok && grade(p)==0 && boot!=null && verify(a[0],boot)){
          grades.put(id,12);hashes.put(id,hash(a[0]));getConfig().set("bootstrap.hash",null);saveConfig();saveData();apply(p);ok=true;
        }
        if(!ok){int n=failures.merge(id,1,Integer::sum);if(n>=getConfig().getInt("settings.login-max-attempts",5)){locked.put(id,System.currentTimeMillis()+getConfig().getLong("settings.login-lockout-seconds",60)*1000L);failures.remove(id);}p.sendMessage(msg("login-failed"));return true;}
        sessions.put(id,System.currentTimeMillis());failures.remove(id);p.sendMessage(color(msg("login-success").replace("%grade%",String.valueOf(grade(p))).replace("%name%",gradeName(grade(p)))));
      }
      case "achange" -> {
        if(!session(p))return true;if(a.length<1){p.sendMessage(color("&cUsage: /achange <new-key>"));return true;}
        String next=String.join(" ",a);if(next.length()<8){p.sendMessage(color("&cUse at least 8 characters."));return true;}
        hashes.put(id,hash(next));saveData();p.sendMessage(msg("key-changed"));
      }
      case "agrade" -> {
        if(!allowed(p,12,12))return true;if(a.length!=2){p.sendMessage(color("&cUsage: /agrade <player> <1-12>"));return true;}
        Player t=Bukkit.getPlayerExact(a[0]);int g;try{g=Integer.parseInt(a[1]);}catch(Exception ex){p.sendMessage(color("&cGrade must be 1-12."));return true;}
        if(t==null||g<1||g>12){p.sendMessage(color("&cTarget must be online and grade must be 1-12."));return true;}
        String key=randomKey(5);grades.put(t.getUniqueId(),g);hashes.put(t.getUniqueId(),hash(key));saveData();apply(t);
        p.sendMessage(color("&aAssigned "+gradeName(g)+" to "+t.getName()+". Temporary key: &e"+key));
        t.sendMessage(color("&aYou are "+gradeName(g)+". Temporary key: &e"+key+"&a. Use /alogin <key>, then /achange."));
      }
      case "makeadmin" -> {
        if(!allowed(p,9,10))return true;if(a.length!=1){p.sendMessage(color("&cUsage: /makeadmin <player>"));return true;}
        Player t=Bukkit.getPlayerExact(a[0]);if(t==null){p.sendMessage(color("&cPlayer must be online."));return true;}
        if(grade(t)!=0||hashes.containsKey(t.getUniqueId())){p.sendMessage(color("&cPlayer already has a staff account."));return true;}
        String key=randomKey(5);grades.put(t.getUniqueId(),1);hashes.put(t.getUniqueId(),hash(key));saveData();apply(t);
        p.sendMessage(color("&aCreated Helper account for "+t.getName()+". Key: &e"+key));t.sendMessage(color("&aYou are Helper. Key: &e"+key+"&a. Use /alogin <key>, then /achange."));
      }
      case "re" -> {
        if(!allowed(p,1,12))return true;if(a.length==0){stopSpectating(p,true);return true;}
        Player t=Bukkit.getPlayerExact(a[0]);if(t==null||t.equals(p)){p.sendMessage(color("&cPlayer not found or invalid target."));return true;}
        if(!spectating.containsKey(id))spectating.put(id,new SavedState(p.getLocation().clone(),p.getGameMode(),p.getAllowFlight(),p.isFlying()));
        p.setGameMode(GameMode.SPECTATOR);p.teleport(t.getLocation());p.sendMessage(color(msg("spectate-start").replace("%player%",t.getName())));
      }
    }return true;
  }
  private void stopSpectating(Player p,boolean tell){SavedState s=spectating.remove(p.getUniqueId());if(s==null)return;p.setGameMode(s.mode);p.setAllowFlight(s.allowFlight);if(s.allowFlight&&s.flying)p.setFlying(true);p.teleport(s.location);if(tell)p.sendMessage(msg("spectate-stop"));}
  private boolean rankCommand(CommandSender s,String[] a){
    if(a.length==0||a[0].equalsIgnoreCase("list")){s.sendMessage(color("&6VOID SMP Grades:"));for(int i=0;i<=12;i++)s.sendMessage(color("&e"+i+". &f"+gradeName(i)));return true;}
    if(a[0].equalsIgnoreCase("reload")){if(!s.hasPermission("voidranks.admin")){s.sendMessage(msg("no-permission"));return true;}reloadConfig();for(Player p:Bukkit.getOnlinePlayers())apply(p);s.sendMessage(color("&aReloaded."));return true;}
    if(a[0].equalsIgnoreCase("info")&&a.length==2){Player t=Bukkit.getPlayerExact(a[1]);if(t==null){s.sendMessage(color("&cPlayer not online."));return true;}int g=grade(t);s.sendMessage(color("&e"+t.getName()+" &7- &f"+g+" "+gradeName(g)));return true;}
    s.sendMessage(color("&cUsage: /vrank <list|info <player>|reload>"));return true;
  }
  @Override public boolean onCommand(CommandSender s,Command c,String l,String[] a){return execute(s,c,a);}
  private record SavedState(Location location,GameMode mode,boolean allowFlight,boolean flying){}
}