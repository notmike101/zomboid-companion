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
- Reads item names, types, counts, equipment slots, durability and shared food metadata. Item counts are separate from remaining food fractions and do not measure medication doses or fluid quantities.
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
javac --release 21 -d build InventoryReader.java InventoryReaderTest.java NearbyFoodReader2.java NearbyFoodReaderTest.java FoodDetails.java FoodDetailsTest.java
java -ea -cp build pzobserver.v3.InventoryReaderTest
java -ea -cp build pzobserver.v3.FoodDetailsTest
java -ea -cp build pzobserver.v3.NearbyFoodReaderTest
jar --create --file inventory-reader-v3.jar --manifest manifest.mf -C build pzobserver/v3/InventoryReader.class -C build 'pzobserver/v3/InventoryReader$ContainerState.class' -C build pzobserver/v3/FoodDetails.class
```

The self-check covers nested inventory, item counts, equipment matching, hidden-object filtering, JSON escaping and cycles. Live verification additionally retrieved the active character's inventory twice with the game process intact. A Java agent class cannot be replaced simply by rebuilding its JAR once loaded. For an expanded implementation in the same running game, use a new package/version so the old class is not silently reused.

## Investigation evidence

REA identified the shipped `projectzomboid.jar` as SHA-256 `e1a69eb743ede60b213a0fe7f8b83d4fcab773036d256cc4543a336f3b058a33`. Its retained archive inspection is in `rea-evidence.json`, Evidence `ev_bb88ea19c0aef3189bd8c10c6a98a3753556812ced5612e55978cc6dd1e07088`. The archive inspection establishes packaged identity and contents, not runtime behavior.

Focused `javap` inspection verified inventory/container APIs and getter bytecode. The shipped `media/lua/client/ISUI/ISInventoryPane.lua:2096` explicitly excludes `item:isHidden()` objects, with a comment identifying them as equipped display models. Live JSON snapshots establish the observed inventory. No broad understanding of every game mechanic is claimed.

## Nearby food and cooking supplies

The second query reads existing container references from `ISPlayerData[player+1].lootInventory.backpacks` through Lua-table `rawget`; it never calls Lua functions or refreshes/selects the UI. Run `& D:\zomboid\read-nearby-food.ps1`. Results go to uniquely named `nearby-food-*.json` snapshots.

The query checks that each non-floor container is still reachable from the current player square using the same `canReachTo` check as the loot pane, is already explored, and is unlocked. It does not populate loot, mark containers explored, or inspect absent containers. It excludes carried inventory and reports a reason for each unreadable reference. This is a scan of the current loot-pane list, not the entire kitchen, room or house. It fails if the player square or pane list changes during the query and checks container memberships before/after reading. It retains the optimistic snapshot limits described above.

All visible contents are recorded so non-`Food` drinks, ingredients, cookware and utensils are not silently dropped. Both readers use the shared food metadata described below. Build 42 fluid-container drinks may not be `Food`; their names are retained, but food portions, freshness and fluid volumes are not measured. No hidden poison, fluid-contaminant or infection fields are read.

Validated on 2026-10-11 against the same live game: the loot pane supplied seven cupboards, one fridge, its freezer and an oven; all ten were explored, reachable and readable. This is a time-specific observation, not a permanent list of nearby containers.

Build the additional query separately, stopping on errors:

```powershell
javac --release 21 -d build InventoryReader.java InventoryReaderTest.java NearbyFoodReader2.java NearbyFoodReaderTest.java FoodDetails.java FoodDetailsTest.java
java -ea -cp build pzobserver.v3.NearbyFoodReaderTest
java -ea -cp build pzobserver.v3.InventoryReaderTest
java -ea -cp build pzobserver.v3.FoodDetailsTest
jar --create --file nearby-food-reader-v3.jar --manifest nearby-manifest.mf -C build pzobserver/v3/NearbyFoodReader2.class -C build pzobserver/v3/InventoryReader.class -C build 'pzobserver/v3/InventoryReader$ContainerState.class' -C build pzobserver/v3/FoodDetails.class
```

The nearby self-check covers freshness states, frozen/cooked flags, non-food cooking-tool metadata and nested explored/locked restrictions before any contents are read. The nearby traversal checks restrictions on every nested container; if a restricted bag is encountered, its root container is reported unreadable without item contents. Final validation re-fetches the actual loot-pane table and checks both button and container references. It reuses the existing getter/JSON helpers and shared `FoodDetails`. The active sources use package `pzobserver.v3`, with `inventory-reader-v3.jar` and `nearby-food-reader-v3.jar`; prior loaded JARs remain local until the game releases them. A new package version prevents the JVM from silently reusing the already-loaded v2 classes.

## Remaining food and states

Each item stays a separate JSON record. `FoodDetails` adds the same fields to carried and nearby food:

- `portion_fraction_remaining`: derived `getHungChange() / getBaseHunger()` only when both are finite, the baseline is nonzero and the ratio is in 0..1. It describes the remaining fraction of the item's base hunger amount, not measured servings or the original recipe/package. Zero means zero of that baseline; null means unknown, never full. Raw getter values and `portion_basis` accompany it.
- `freshness`: the game's fresh/stale/rotten flags, including for canned/dried food. `freshness_time_remaining` is null: exact age/spoilage thresholds are debug-only information and do not yield a reliable real-time deadline while storage conditions change.
- `frozen` is the stored engine flag; `freezing_progress_fraction` is stored 0..100 progress divided by 100, not elapsed or remaining time. `freezing`/`thawing` are derived from progress, can-be-frozen and the outer container using pure getters. Outside a freezer, positive progress means thawing. Inside a freezer, power-dependent direction is null/unknown. An item can remain flagged frozen while thawing.
- `cooked`, `burnt`, `cookable`, `cooking_state`, `dangerous_uncooked`: burnt takes precedence over cooked, then raw for uncooked cookable food. Food that cannot be cooked is not described as raw merely because `isCooked()` is false. The dangerous-uncooked flag is the normal tooltip warning, suppressed for cooked/burnt items.

Evidence from the installed Build 42.21 JAR: `getHungChange` and `getBaseHunger` are field getters. The normal eating-menu tooltip in `media/lua/client/ISUI/ISInventoryPaneContextMenu.lua:2059` uses their ratio to translate original quarters into remaining amounts. `getCurrentUses`/`getCurrentUsesFloat` express hunger units and are not serving counters. `Food.DoTooltip` gates exact Age/OffAge/OffAgeMax behind debug mode. Avoid `isWholeFoodItem`/`isNormalAndFullFood`, which can create a temporary item, and avoid hidden poison/nutrition fields and debug timers. `isFreezing`/`isThawing` call `ItemContainer.isPowered` then `IsoObject.checkObjectPowered`, which writes the world-object power cache; neither getter is called by this observer. Stored progress and outer-container getter bytecode were checked instead. Numeric portions may be unknown for zero-baseline spices, modified recipes or unsupported fluids. None of these fields establishes recipe eligibility.

Shared tests cover half/full/zero portions, zero/nonfinite/out-of-range baselines, separate same-named variants, food states and non-Food unknowns. These remain optimistic snapshots: food amounts and states can change during collection even when container membership remains stable.

## Shared Codex and OMP skills

Launch either tool in `D:\zomboid`. Both discover the same two project-owned skill files in `.agents/skills`:

| Feature | Codex | OMP |
|---|---|---|
| Carried items, bags, equipment and food details | `$zomboid-carried-inventory` | `/skill:zomboid-carried-inventory` |
| Nearby fridge/freezer/cupboard food and cooking supplies | `$zomboid-nearby-food` | `/skill:zomboid-nearby-food` |

Each skill calls its existing PowerShell launcher and requires a successful fresh snapshot. Reports retain capture time, per-item amount/state differences, and unknown fields. Nearby reports list unreadable references and absent requested container types; a top-level `ok` is not proof of a complete kitchen scan. No automation or OMP-as-subagent integration is included.

## Public backup boundary

The Git allowlist includes only reviewed reader source, tests, manifests, launchers, skill instructions and this documentation. Build products/JARs, live snapshots, saves/assets, logs, detailed archive evidence, old reader experiments and local `.omp` model configuration stay local and ignored. Build the JARs after cloning. Paths in source are intentional installation checks for this setup, not portable configuration; no game installation or save data is included.

Extend these queries with narrowly scoped getters for player-visible information when requested; do not add an arbitrary execution endpoint or unrestricted world dump.
