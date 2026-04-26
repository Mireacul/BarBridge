BarBridge — Boss Bar Plugin & API for Spigot 1.8.x
====================================================

Commands and usage
-----------------
Drop BarBridge-1.0.0.jar into your server's plugins/ folder.

ProtocolLib is OPTIONAL on the server but HIGHLY recommended to cover all edge cases.

Commands:

  Permission required: barbridge.admin

  /bar set <player|*> <message>
  
  /bar timed <player|*> <seconds> <message>
  
  /bar msg <player|*> <message>
  
  /bar health <player|*> <percent> [message]
  
  /bar remove <player|*>

Subcommand aliases:
  msg     = message, update
  remove  = clear

All messages support &-style color codes.


USING AS AN API
-------------------------------------

1. Add BarBridge-1.0.0.jar to your plugin's libs/ folder (using the same
   nested-repo pattern, OR install to your local m2 repo)

2. Add depend in your plugin.yml:

   depend: [BarBridge]

3. Call the API:

   import com.mireacul.barbridge.BarBridge;

   BarBridge api = BarBridge.getInstance();
   
   api.setBar(player, "&eWelcome!"); 
   
   api.setBar(player, "&eThis Bar is at Half Health", 50f);
   
   api.setTimedBar(player, 30, "&aFight starts in 30s");
   
   api.updateMessage(player, "&bResolved");
   
   api.updateHealth(player, 75f);
   
   api.updateAll(player, "&cBoss", 100f);
   
   api.removeBar(player);

   if (api.hasBar(player)) {
   
       String msg = api.getMessage(player);
       
       float pct  = api.getHealth(player);
       
   }


API REFERENCE
-------------

  void   setBar(Player, String)                      - persistent at 100%
  
  void   setBar(Player, String, float percent)       - persistent at given %
  
  void   setTimedBar(Player, int seconds, String)    - drains 100->0, auto-removes
  
  void   updateMessage(Player, String)               - text only
  
  void   updateHealth(Player, float percent)         - fill only
  
  void   updateAll(Player, String, float percent)    - both
  
  void   removeBar(Player)                           - hide
  
  boolean hasBar(Player)                             - active?
  
  String getMessage(Player)                          - current text or null
  
  float  getHealth(Player)                           - current % or -1

How BarBridge Works
-----------------
Minecraft 1.8 doesn't have a real boss bar API. The only way to show a
boss bar to a player is to spawn a wither or ender dragon near them.
The boss bar in their HUD is automatically driven by that entity's
custom name and health.

BarBridge fakes a wither without actually spawning one on the server.

For each player with a bar, BarBridge sends them a packet that says
"hey, there's a wither at this location." The wither only exists on
that one client. The server has no entity, no AI, no collision.

Each player gets their own personal wither. That way every player can see
a different message and a different timer at the same time.


Why it doesn't make smoke or noise
----------------------------------
Withers normally do two annoying things:
  1. They emit black smoke particles you can see from a distance.
  2. They make ambient growling sounds.

The fix for both is the same: spawn the wither 256 blocks above the player.
That's higher than the 1.8 build height, so the wither is in dead sky
where no player can ever look. Smoke gets emitted but nobody is close
enough to render it. Sounds attenuate to silence at that range.



Following the player
--------------------
The wither has to stay near the player or the boss bar disappears from
their HUD. Every tick, BarBridge checks if the player moved:
  - If they crossed a chunk boundary: destroy and respawn the wither.
  - If they just walked a bit: send a teleport packet.
  - If they didn't move: do nothing.

This keeps the bar visible without spamming packets.


ProtocolLib (optional)
----------------------
ProtocolLib is used for two edge cases:
  1. Cancel a player's "attack entity" packet if they somehow target the
     fake wither (basically impossible at Y+256, but free safety).
  2. Mute any wither sounds that do reach the player.

Both are nice-to-haves. The +256 height already handles 99% of the
problem on its own, so ProtocolLib is a soft dependency the plugin
runs without it.


BUILDING
--------

ProtocolLib.jar is already pre-positioned at:
  libs/com/comphenix/protocol/ProtocolLib/local/ProtocolLib-local.jar

Build:
  mvn clean package

Output:
  target/BarBridge-1.0.0.jar




THREADING
---------

The internal bar map uses ConcurrentHashMap, so concurrent reads and writes
from multiple threads will not corrupt state. In practice most callers
invoke the API from the main server thread (commands, Bukkit events, sync
BukkitRunnables), and that remains the recommended pattern since NMS
packet sending is most commonly done there.
