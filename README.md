# Project Zomboid read-only companion

The first supported query is your active single-player character's inventory, including equipped items and all containers carried by the character. It reads the live game through a one-shot Java agent, using the standard Attach API. It does not rely on an autosave, game input, debug mode, or a Lua mod.

## Read the inventory

With the game already running, run this from PowerShell:

```powershell
& D:\zomboid\read-inventory.ps1
```

The script locates the running game, validates its executable, prints the JSON result and writes a uniquely named snapshot under `D:\zomboid\snapshots`. An observer error produces an error snapshot and a failing exit code. Request another snapshot if a transfer changed inventory during collection. The tool never pauses or unpauses the game. It also works while the game is paused.

Validated against Build **42.21.0 4a0e9546ec**, bundled Java **25.0.1**, on 2026-10-11. Java 21 is installed for compiling and running the launcher. The selected executable is `C:\Games\steam\steamapps\common\ProjectZomboid\ProjectZomboid64.exe`. This tool is deliberately tied to this installation and single-player mode.

## Fair-play boundary

- Reads only the active character's carried inventory and equipment.
- Uses the game's `isHidden()` filter, matching the inventory pane. Internal injury display models are excluded. They must not be interpreted as a health diagnosis.
- Reads item names, types, item counts, equipment slots and durability. Item counts do not measure remaining medication doses or fluid quantities.
- Does not inspect nearby loot, enemies, unexplored map areas, hidden infection, random outcomes or other characters.
- Contains no gameplay setters, input, arbitrary evaluation, class transformers, game event hooks, persistent worker threads or network listeners. It does not edit the installation, save data or mod configuration.

Loading the agent adds observer classes to the running JVM and can cause a brief JVM attachment hitch. The classes remain loaded until the game exits; no observer task continues between requests. Java logs a standard dynamic-agent warning. The launcher detaches after every request and never terminates the game.

## Consistency and limits

Snapshots check each traversed container's item membership and all equipment slots before/after collection. A character switch, detected transfer, duplicate item, cycle, missing required game class or excessive nesting fails the query. This is an optimistic read on an observer thread, not an atomic game-thread transaction: durability, quantities and names may change during collection, and a change that reverses before the final check can escape detection. The snapshot includes its UTC timestamp and collection duration.

The reader has a 32-level nesting limit and 10,000 visible-item limit. Mod items work through the inherited item/container API; mods that override getter behavior have not been exhaustively tested. Recheck APIs after game updates. Snapshot files contain character/item information and remain local.

## Build and self-check

Run these separately from `D:\zomboid`, stopping if any command fails:

```powershell
New-Item -ItemType Directory -Path build -Force | Out-Null
javac --release 21 -d build InventoryReader.java InventoryReaderTest.java
java -ea -cp build pzobserver.v2.InventoryReaderTest
jar --create --file inventory-reader-v2.jar --manifest manifest.mf -C build pzobserver/v2/InventoryReader.class -C build 'pzobserver/v2/InventoryReader$ContainerState.class'
```

The self-check covers nested inventory, item counts, equipment matching, hidden-object filtering, JSON escaping and cycles. Live verification additionally retrieved the active character's inventory twice with the game process intact. A Java agent class cannot be replaced simply by rebuilding its JAR once loaded. For an expanded implementation in the same running game, use a new package/version so the old class is not silently reused.

## Investigation evidence

REA identified the shipped `projectzomboid.jar` as SHA-256 `e1a69eb743ede60b213a0fe7f8b83d4fcab773036d256cc4543a336f3b058a33`. Its retained archive inspection is in `rea-evidence.json`, Evidence `ev_bb88ea19c0aef3189bd8c10c6a98a3753556812ced5612e55978cc6dd1e07088`. The archive inspection establishes packaged identity and contents, not runtime behavior.

Focused `javap` inspection verified inventory/container APIs and getter bytecode. The shipped `media/lua/client/ISUI/ISInventoryPane.lua:2096` explicitly excludes `item:isHidden()` objects, with a comment identifying them as equipped display models. Live JSON snapshots establish the observed inventory. No broad understanding of every game mechanic is claimed.

## Nearby food and cooking supplies

The second query reads existing container references from `ISPlayerData[player+1].lootInventory.backpacks` through Lua-table `rawget`; it never calls Lua functions or refreshes/selects the UI. Run `& D:\zomboid\read-nearby-food.ps1`. Results go to uniquely named `nearby-food-*.json` snapshots.

The query checks that each non-floor container is still reachable from the current player square using the same `canReachTo` check as the loot pane, is already explored, and is unlocked. It does not populate loot, mark containers explored, or inspect absent containers. It excludes carried inventory and reports a reason for each unreadable reference. This is a scan of the current loot-pane list, not the entire kitchen, room or house. It fails if the player square or pane list changes during the query and checks container memberships before/after reading. It retains the optimistic snapshot limits described above.

All visible contents are recorded so non-`Food` drinks, ingredients, cookware and utensils are not silently dropped. Solid `Food` items receive the game's fresh/rotten/frozen/cooked/burnt flags. The reported fresh/stale label reflects game getters even for canned or dried items, not an independent shelf-life assessment. Build 42 fluid-container drinks may not be `Food`; their names are retained, but freshness and fluid volumes are not measured. No hidden poison, fluid-contaminant or infection fields are read.

Validated on 2026-10-11 against the same live game: the loot pane supplied seven cupboards, one fridge, its freezer and an oven; all ten were explored, reachable and readable. This is a time-specific observation, not a permanent list of nearby containers.

Build the additional query separately, stopping on errors:

```powershell
javac --release 21 -d build InventoryReader.java InventoryReaderTest.java NearbyFoodReader2.java NearbyFoodReaderTest.java
java -ea -cp build pzobserver.v2.NearbyFoodReaderTest
java -ea -cp build pzobserver.v2.InventoryReaderTest
jar --create --file nearby-food-reader-v2.jar --manifest nearby-manifest.mf -C build pzobserver/v2/NearbyFoodReader2.class -C build pzobserver/v2/InventoryReader.class -C build 'pzobserver/v2/InventoryReader$ContainerState.class'
```

The additional self-check covers freshness states, frozen/cooked flags, non-food cooking-tool metadata and nested explored/locked restrictions before any contents are read. The nearby traversal checks restrictions on every nested container; if a restricted bag is encountered, its root container is reported unreadable without item contents. Final validation re-fetches the actual loot-pane table and checks both button and container references. The new agent class reuses the existing getter and JSON helpers. `NearbyFoodReader2.java` and `nearby-food-reader-v2.jar` are the active implementation; the original nearby reader is retained only because its JAR is held open by the existing game process. As with the original reader, use a new class name for a revised agent after its class has been loaded in a running game.

Extend these queries with narrowly scoped getters for player-visible information when requested; do not add an arbitrary execution endpoint or unrestricted world dump.
