# Humanised Baritone

A client-side Fabric mod for **Minecraft 26.2** that brings a full Baritone-style automation bot to the game, with one
big difference: everything it does is **humanised**. The camera turns like a hand on a mouse, keys are pressed with
human timing, clicks land at different spots, routes differ from run to run. Nothing is ever exactly the same twice.

Press **`'`** (apostrophe) in game to open the control panel.

## Requirements

* Minecraft **26.2**
* Fabric Loader **0.19.5** or newer
* Fabric API **0.161.0+26.2** (or a newer 26.2 build)
* Java **25**

Drop `humanised-baritone-1.0.0.jar` into your `mods` folder. Build it yourself with `./gradlew build`
(the jar ends up in `build/libs`).

## The panel

| Tab | What is in it |
|---|---|
| **Dashboard** | Live status and progress, quick toggles, a travel box, task buttons (mine, farm, follow, explore, wander, hunt, collect, build, guard, sleep, store), area selection, and a command console with history |
| **Logging** | What the scanner found in the world and dimension you are in (stashes, spawners, players, portals, valuable items, your deaths...) as a filterable list with Go / Map / Done buttons, and the settings of the scanner below it |
| **Map** | The terrain you have seen, you on it, the other players in view and every logged find, with zoom, drag, pins and a legend; saved separately for every world and dimension |
| **General** | Remember bot enabled state, chat commands, feedback, passive guard |
| **Humanise** | Camera, movement feel, action timing, aim spread, random variation, attention drift, random seed |
| **Movement** | Sprint, gap jumps, swimming, climbing, doors, fall limits, water bucket clutch and its landing correction, pillaring, digging down, bridging, the elytra jump and its helper, the wander radius |
| **World** | Breaking and placing rules, tool handling, never-break / building-block lists, mining, farming and building options |
| **Combat** | Fight back / hunt, reach, cooldown timing, crits, sprint resets, strafing, shield, bow, retreat health, creeper tactic, target lists |
| **Safety** | Lava, fire, hazard blocks, mob avoidance, endermen, breathing, emergencies |
| **Survival** | Auto-eat, armor, totem, pick-up, junk dropping |
| **Chat Games** | Automatic replies for unreverse, unscramble and number challenges, with separate toggles and random delay ranges |
| **Interface** | Interface style (Minecraft, Modern, Fun, Relaxing), accent colour, panel scale, animations, status overlay, in-world path overlay |
| **Advanced** | Planner limits and diagnostics |
| **Profiles** | Humanising presets (Casual, Natural, Focused, Speedrun), saved profiles, waypoints |

Every setting has a description (shown in the footer on hover), right-click resets a slider and double-click lets you
type an exact value. The search box finds any of the ~170 settings.

Enable **General > Remember bot enabled state** to keep your last ON/OFF choice when rejoining, switching worlds or
restarting Minecraft. It is off by default; with it off, the bot starts disabled on each join. Tasks are cleared when
disconnecting even when the enabled state is remembered.

### Chat games

Turn the bot **ON**, open **Chat Games**, and enable the challenges you want to answer. Each has minimum and maximum
response delays in seconds (defaults: 1–3 seconds). A fresh random delay is chosen for every challenge. Equal values
give a fixed delay; reversed bounds are automatically sorted. These settings are saved with the other settings and profiles.

* **Unreverse the letters** reverses the supplied text, preserving capitals and digits.
* **Unscramble the word** matches letters against registered Minecraft block/item names and their readable IDs,
  ignoring case, spaces and underscores. It skips unknown words and makes one guess if several names match.
* **Guess the number** sends one random integer within the announced range, including both endpoints.

The listener recognises server announcements such as `Unreverse the letters to win!`, `Unscramble the word to win!`,
and `Guess the number between 1 and 100!`. The puzzle can follow the heading on the same line (e.g. `: dnomaiD`),
or on an arrow-prefixed line such as `➤ dnomaiD` within five seconds. Minecraft colour formatting is ignored.
Player chat and action-bar messages are excluded. Duplicate puzzles are suppressed for 60 seconds. Disabling a
challenge, switching the bot off, pausing, stopping, or disconnecting cancels its pending reply.

### Interface styles

**Interface > Interface style** restyles the whole panel and the in-game status overlay at once, font, layout, shapes and
motion included. It switches live, also while the panel is open.

| Style | Look |
|---|---|
| **Minecraft** | The original compact dark panel with a side bar |
| **Modern** | DM Sans, graphite surfaces, grouped side bar, a light that runs along the panel border, a cursor spotlight, sheen on hovered buttons, ripples on clicks |
| **Fun** | Fredoka, sticker-style outlines with hard shadows, a tab strip on top whose active tab unfolds, a bouncing mascot, springy controls (the switch has a face), confetti behind the panel and on every click |
| **Relaxing** | Lora, soft frosted glass, a rail of round tab icons, a breathing glow, drifting fireflies and stars, a light that trails the cursor, slow gentle motion |

Every style honours **Animations** (off = still scenery, no motion) and the accent colour (punchier in Fun, softer in
Relaxing). The fonts are open licence files shipped in the jar, see `assets/humanised_baritone/font/README.txt`.

## Commands

Commands work in the console on the dashboard and in chat with the prefix `#` (changeable).

```
enable | disable | stop | pause | resume | status | help
goto <x y z | x z | y | waypoint>      ~ means relative to you
mine [count] <block...>                mine diamond | mine 20 iron_ore | mine logs
farm [radius]        collect [radius]      follow <player|mob>      explore [x z]
wander [radius]      hunt                  guard                    sleep
store [item]         fetch <item>
tunnel [length] [width] [height]
then <command>                         run a command after the current task instead of replacing it: then mine 10 logs
sel pos1|pos2 [x y z] | sel clear | sel fill <block> | sel walls <block> | sel none
build <file> [x y z]                   load a .schem, .litematic or .nbt from config/humanised_baritone/schematics and show it in the world
build confirm | cancel                 start building what is on show / drop it
build here | move x y z | nudge dx dy dz | rotate      put the preview somewhere else, or turn it a quarter
wp save|goto|remove|list <name>
log [list [n]] | goto <n> | check <n> | clear [kind] | scan | mark <name>      the finds of the scanner (also: finds)
set <setting> <value>    get <text>    reset [setting]    preset <name>    profile list|save|load|delete <name>
```

Keys (rebindable): `'` open the panel, `;` emergency stop, and an unbound key to toggle the bot.

## Logging and the map

A scanner watches what your game has loaded around you and writes down what is worth remembering. It only **reads** what the
server already sent to your client (block entities, blocks and entities of the chunks you can see); it sends nothing and
changes nothing. That is the same data the chest ESP or block search of a hacked client draws its boxes from, so whatever such
a module would show you can be logged here; what the server does not send is invisible to both (anti-xray plugins hide
ores or swap them for fakes). There is no link to any other client, and none is needed. Whether a feature like this is
allowed is up to the server you play on (see Notes); *Only exposed blocks* limits the block search to what could be seen
from a cave or the surface, and the whole scanner can be switched off with *Scanner*.

| Kind | What is logged | Settings (Logging tab) |
|---|---|---|
| **Stash** | A place where many containers (chests, trapped chests, barrels, ender chests, shulker boxes) stand close together; a double chest counts once. Grows and shrinks with what is there | *Stash finder*, *Containers for an alert* (default 10), *Stash area* (default 16 blocks, every direction), *Container types* |
| **Spawner** | Mob spawners (with the mob), trial spawners, vaults | *Spawners and vaults* |
| **Player** | Every other player that comes into view: name, position, what they hold, armor. Reported again when they return after a while | *Players*, *Same player again after* |
| **Search** | Veins of the blocks you list (diamond ore and ancient debris to start with), found in any loaded chunk, also behind rock | *Block search* (off by default), *Search blocks*, *Blocks per find*, *Only exposed blocks* |
| **Portal** | Nether portals, end portal frames (and how many hold an eye), end portals, end gateways | *Portals* |
| **Base marker** | Beacons and conduits | *Beacons and conduits* |
| **Valuable item** | Item frames and dropped items from a list (elytra, totems, netherite, enchanted golden apples...) | *Valuable items*, *Valuable item list* |
| **Sign** | Signs with writing on them | *Signs with text* (off by default) |
| **Death** | Where you died | *Your deaths* |
| **Marker** | Your own, dropped on the map | |

**Alerts.** When something new is logged (or a stash grows a lot) a message appears in your chat, for example
`[HB] Stash: 17 containers (12 Chest, 3 Barrel, 2 Shulker Box) within 16 blocks at [34, -60, 34] 48 blocks away`. Click the
coordinates to get the travel command in the chat box (`#log goto 3`). A chime plays with it. Alerts come three at a
time every half second; a flood of more than a dozen (a whole base coming into view) is cut to three lines and a count. *Chat alerts* and *Alert sound* switch these off; the finds are logged anyway.
*Highlight finds* draws boxes around the finds near you (and around each container of a stash) in the world.

**Logging tab.** A summary card (which world and dimension, one chip per kind with its count: click to show or hide, right-click
to show only that kind), then the list: newest first, nearest first or by kind. Hover or select a row for its buttons:
**Go** (walk next to it), **Map** (show it on the map), **Done** (it has been dealt with: no highlight any more) and **x**
(remove). **Scan** looks at everything loaded once more (use it after changing what the scanner looks for), **Clear** removes
the kinds that are shown. The settings of the scanner follow below the list.

**Map tab.** The terrain you have seen, painted like a vanilla map (shading follows the slope, water the depth; in the Nether
the floor you were at), with you as an arrow, the other players in view as white dots and every find as a coloured marker. While the bot
travels, its route is drawn as green dots with a ring on the goal. Drag to move, wheel to zoom, double-click to zoom in on a spot, **Follow** keeps it centred on you, right-click drops a pin
(**Go** walks there, **Mark** keeps it as a marker of your own), click a marker for its **Go**, **Copy** (the coordinates) and
**Log** buttons. The legend on the left turns kinds on and off. **Clear map** forgets the terrain of the dimension shown.

**Per world and dimension.** Finds and map are stored separately for every world (a server's address, or a singleplayer save)
and every dimension in it, under `config/humanised_baritone/finds/<world>/<dimension>/` (`finds.json` and `tiles/*.png`,
one image per 256 x 256 blocks). Both pages show the world and dimension you are in; the two buttons at their top cycle
through everything that is stored, so you can look at another server's stash list or the Nether map from anywhere (*Live*
brings you back). Up to 4000 entries are kept per dimension. The scanner works in slices of a few milliseconds per tick.

Commands: `log` lists the newest finds, `log goto <n>`, `log check <n>`, `log clear [kind]`, `log scan`, `log mark <name>`.

## Elytra jump

With an elytra worn the bot can **bounce**: jump on the exact tick of every landing and open the elytra again in the air.
No fireworks, only timed jumps. On open flat ground that is 30 to 38 blocks a second (a sprint is 5.6, a sprint hop 6.7),
reached in about five seconds and a hundred and fifty blocks.

Why the timing matters: a glide that touches the ground ends only when the server says so, a tick later. On that first tick the
game still counts the player as gliding, so there is no ground friction, and a jump right then keeps all the speed (and adds the
+0.2 of a sprint jump). One tick later the friction would take almost half of it. In the air the glide has to be opened again with
a fresh press of the jump key, so the bot lets go of the key the tick after the jump and presses it again the moment the glide is
gone. The result is ten ticks a bounce, no tick of it a plain fall, and a speed that depends on the jumps alone (any camera pitch
from 70 to 89 degrees measured the same).

* **Wander** (`wander [radius]`, Dashboard > Wander): roams with no goal. It picks a heading that bends a little from the last,
  bounces along it while the ground is clear, flat and plain, looks around for a moment, and starts the next leg. It never goes
  further from where it started than **Wander radius** (250 blocks by default, 0 = no limit) and turns for home near the edge.
  Where a bounce is not possible (no elytra, rough ground, trees, water) it walks to a spot a few dozen blocks away instead, with
  the usual pathfinding. It runs until it is stopped.
* **Elytra jump helper** (Movement > Elytra, off by default, or `set elytra.helper on`): the bot has to be ON. Hold forward and look
  steeply down (55 degrees or more) with an elytra worn and the bot presses jump and sprint for you; you steer with the mouse, and
  walking normally (camera level) is left alone. Let go of forward or crouch and it stops. **Brake before obstacles** (on) makes it
  stop jumping early enough to come to a halt in front of a wall or a step up: a wall at full speed costs ten hit points or more on a
  real server. It needs three free blocks above the floor, a bounce under a lower ceiling is put back by the server: a tunnel for it has
  to be three high.
* **On the way somewhere**: with an elytra worn, a `goto` (and the legs of `explore`) over a long stretch of straight, flat, open ground
  is bounced instead of run: 300 blocks take 15 seconds instead of 45. The ground is checked as it is flown over, and when the stretch
  ends the bot stops and plans the rest as usual. It needs **Humanise > Straight runs** on.
* **Spare the elytra**: the bounce stops below this durability (8 percent). A bounce itself costs the elytra next to nothing (the game
  wears it for every twenty ticks of an unbroken glide, and a bounce never glides nine ticks in a row: 500 ticks of it left the elytra
  without a scratch), a long plain glide would.

What it looks at before every jump: a lane a block and a half wide along the line it flies, as far as the stop needs (about 40
blocks at full speed). The ground has to be level or one block lower and plain (no ice, soul sand, honey, slime, water, lava, fire,
hazard blocks or holes), with three free blocks above it for the whole hop. A step up of any height is a wall,
and chunks that are not loaded count as walls. Hunger of 6 or less (no sprint), a missing or worn-out elytra, and the potion
effects that change the length of a hop (jump boost, slow falling, levitation) stop it as well. The armor management never swaps
a worn elytra for a chestplate.

The elytra is only opened for the length of each bounce; this is not flying.

## Building schematics

**Dashboard > Build...** (or `build <file>`) lists the files in `config/humanised_baritone/schematics` (`.litematic`, `.schem`,
`.nbt`) and loads the one you click. Nothing is built yet: the schematic is shown in the world the way Litematica shows a
placement, a few blocks in front of you, with its lowest layer at your feet.

| In the world | Meaning |
|---|---|
| Ghost block with a blue outline | Still missing. The ghost is the block's real model, so a chest, a hopper or a trapdoor looks like itself |
| Red box | A different block stands where the schematic has another one (a *wrong* block) |
| Orange box | The right block, but turned the wrong way, or the wrong slab half, or without its water (*orientation*) |
| Purple box | A block that does not belong inside the schematic's box, and the temporary blocks the bot puts down to climb or to click against |
| Nothing | The right block is there: a position leaves the preview the moment it is correct |

The dashboard gets a **Schematic** card with the size, the four counts, X/Y/Z boxes, nudge buttons that move the preview
the way you face (Left, Right, Back, Forward, Down, Up, in steps of 1, 5 or 16), **Here**, **Rotate**, **Cancel** and
**Confirm and build**. The bot only starts when you press Confirm (or type `build confirm`). The preview stays while the
bot works and shows what is left. The panel can be closed with its key to look at the preview; the card is there again when
it is opened.

How the bot builds it: layer by layer, from the bottom up (**World > Building > Build order**), breaking what is wrong first.
Every click is chosen so that the block comes out exactly as planned: it predicts the result of a click with the game's own
placement code and picks a standing spot, a face and a hit point that give the right facing, slab half or axis, and places
nothing rather than something wrong. It climbs with pillars and bridges of cobblestone (or what **Building blocks** lists),
keeps those out of the cells the schematic still needs, and takes them away again when it is done. A block with nothing to
click against (a trapdoor between rails, a top slab in mid-air) gets a temporary block next to it from which the click gives
the right state; if every cell beside it is taken by a block of the plan already, one of those (a rail, a slab, a plain
block) is taken out for the time being and put back afterwards. Water is poured from a bucket only when the pond is sealed
(it would flood the site otherwise): the bot works out where the water would go the way the game does, so a top slab or a
closed top-half trapdoor under it counts as a floor, a chest or a bottom slab beside it does not. A waterlogged slab is
placed dry first and filled later; a coral block that dies for lack of water is replaced once the water is there.

Not built: the items in chests and other containers, entities (a minecart in the schematic) and the signal strength of
redstone. In survival the bot needs the materials in its inventory and says what is missing; each water source or waterlogged
block takes a full water bucket.

Limit: some blocks get their facing from where the player *looks*, not from the face that was clicked (a piston, a dispenser
or a dropper facing down means "looking up at it"). The bot can only place those from a spot below them with a free line of
sight, so when the layers underneath are already built (the twelve down-facing pistons over the pond of `Pickle_Farm`) they
are left undone, the log says so, and anything that needs them (a pickle that is to sit in sealed water) waits too. Litematica
gets around this with a server-side "easy place" protocol that a vanilla server does not have.

## What the bot can do

* **Pathfinding**: A* search on a worker thread with costs in game ticks, partial paths that are extended while walking,
  re-planning when the world or the player disagrees with the plan.
* **Movement**: walking, sprinting, diagonals, stepping up, dropping down, ladders and vines, swimming, gap jumps
  (up to four blocks with a proper run-up), pillaring, digging down, bridging, doors and gates. Across open, flat
  ground the planner's block-by-block route is straightened into one run (the staircase of diagonal and straight moves a
  grid search produces is not how anyone walks) that is held at a full sprint; near ledges, hazards, water, hostile mobs
  and anywhere that needs a jump or a placed block the bot keeps to the route closely. Steps that finish hand over to the
  next within the same tick, so forward and sprint are never let go of between blocks. Awkward floors are
  handled: from soul sand, mud, chests and other low blocks it pillars onto a full block before bridging; it never tries
  to jump off honey; it takes no running jumps off slow soul sand or slime; and on ice it lets go early so it does not
  slide off at corners.
* **Breaking and placing**: best tool selection, durability protection, safety checks against flooding and falling
  blocks, tunnelling through rock, building with orientation-aware placement.
* **Fighting**: focus on one enemy at a time, approach, stepping back to stay just out of a melee mob's reach while the
  sword still reaches it, cooldown-timed swings, jump crits, strafing, shield, bow with lead and drop compensation,
  creeper tactics, retreating or eating a golden apple at low health, and never walking into lava or off a ledge to
  get at a target.
* **Staying alive**: lava and fire escape, drowning prevention, water bucket clutch (planned and accidental; in the air
  it drifts to the middle of the block it would land on, so the water is never missed on an edge, and on the ground it
  crouches and takes the water back right away so the current cannot carry it off), eating, emergency golden apples
  (also in the middle of a fight), armor and totem management, optional automatic respawn.
* **Tasks**: mining, farming with replanting, following, exploring, wandering (with timed elytra jumps), hunting, guarding,
  collecting drops, clearing, filling, building schematics, tunnelling, sleeping, storing and fetching items in chests.

## How the humanising works

* **Camera**: behaviours only say where to look. A per-frame controller moves the real camera along a damped spring whose
  speed follows Fitts' law, with reaction delay, hand tremor, occasional overshoot and correction, and a low-pass filter
  so accelerations ramp. The result is applied in **whole mouse counts at your sensitivity** through the same code path a
  physical mouse uses. Nothing ever snaps.
* **Input**: movement goes through a replacement `ClientInput`, so vanilla sprint, sneak and jump logic all apply.
  Mouse buttons are pressed through the real key mappings and a click only happens when the game's own crosshair ray
  is on the intended block or entity.
* **Variation**: a slow sideways drift on straight runs (as far as the open ground allows) and rounded corners while
  walking, micro-hesitations and strafe taps, random jump timing
  inside the safe window, glances around, idle fidgets, log-normal reaction times, aim points spread over block faces
  with the smallest camera movement preferred, path cost noise so equal routes differ, and a slowly drifting
  attention level that changes reaction time and steadiness.
* Everything is tunable in **Humanise**; the presets give a quick start and a seed makes a session repeatable.

## Notes

* This is a client-side automation tool. Whether automation is allowed is decided by the server you play on: check its
  rules. Single-player and servers that permit bots are the intended use.
* World data is read from chunks the client has loaded. Plans reaching into unloaded chunks are cut at the frontier and
  continued as the world loads.
* Directional blocks (stairs, slabs, logs, wall-mounted blocks) are placed by simulating the game's own placement logic:
  the bot picks where to stand and where to click so that they come out as planned, and puts a block off until its
  neighbours exist if it cannot be right yet. States that only follow from the neighbours (door hinges, stair corner
  shapes) cannot be chosen.

## Development

```
./gradlew build                              # compile and package
./gradlew chatResponderTest                  # deterministic chat parsing, guesses and timing checks
HB_TEST=chat ./gradlew runClientGameTest      # real chat replies and Chat Games page in every style
HB_TEST=remember ./gradlew runClientGameTest # enabled-state persistence across world joins
HB_TEST=move   ./gradlew runClientGameTest   # in-game suites: move, task, build, combat, terrain, nature, life, human, gui, surface, clutch, feel, feelnature
HB_TEST=schematic ./gradlew runClientGameTest # Build... > list > card buttons > confirm, with real mouse clicks
HB_TEST=preview   ./gradlew runClientGameTest # the in-world preview and its colours (HB_PERF=1: frame rate with a 6000 block hall)
HB_TEST=scan      ./gradlew runClientGameTest # the scanner: stashes, search, spawners, players, portals, alerts in the chat, persistence per world and dimension, the map, the Logging and Map pages with real clicks
HB_TEST=scanmap   ./gradlew runClientGameTest # the map over a natural world (screenshots at four zoom levels) and what the scanner costs per tick (HB_DISTANCE=32 for a long view distance)
HB_TEST=elytra    ./gradlew runClientGameTest # the elytra jump: helper, wall, tunnel, gates, wear, the engine of the wander task, goto with an elytra, the task itself, the armor guard (traces in build/elytra)
HB_TEST=elytranature ./gradlew runClientGameTest # wander and a far goto with an elytra in a natural world, no damage protection (HB_REPEAT=n repeats)
HB_TEST=pickle HB_MODE=creative ./gradlew runClientGameTest  # builds Pickle_Farm.litematic from scratch and compares it block by block
```

The tests launch the real game, create a world, run the bot through scenarios and assert on the outcome. Set
`HB_ONLY=name1,name2` to run only some scenarios of a suite and `HB_REPEAT=n` to repeat the mine and hunt scenarios.

`clutch` is the water bucket suite: falls, planned drops off a tower, the rim and edge cases, the current. For a failure that comes and
goes, `HB_ONLY=planned HB_REPEAT=14` runs the planned drop 14 times, `HB_DIR=all` walks off towards every side in turn and `HB_TOWER=h`
sets the height. `HB_FPS=n` caps the frame rate (a game left alone drops to 30 fps after a minute without input and to 10 after ten) and
`HB_TRACE=2` prints a line per tick with the camera, the keys and the water.

`feel` and `feelnature` measure how the walking looks: speed, the share of ticks with forward and sprint held, stops,
turns and backtracking, on flat scenarios and over natural terrain. Each run also writes the per-tick trace to
`build/feel/NAME.csv`.

License: MIT.
