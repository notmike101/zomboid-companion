---
name: zomboid-nearby-food
description: Use when inspecting nearby Project Zomboid fridge, freezer and cupboard food or cooking supplies, including remaining food fractions and freshness/cooking/freezing states, through the existing live storage reader.
---

# Nearby food and cooking supplies

Inspect the existing nearby loot-pane references for meal planning. Keep carried inventory separate; use the carried-inventory skill when the user also requests it, retaining each query's timestamp.

## Read

Run once in PowerShell, using the host's command tool:

```powershell
& D:\zomboid\read-nearby-food.ps1
```

For a new PowerShell process, use `pwsh -NoProfile -File D:\zomboid\read-nearby-food.ps1`. Read [the project README](../../../README.md) for build requirements and compatibility limits. Report missing dependencies instead of installing, rebuilding or changing game configuration during an inspection request.

The command prints JSON followed by a `Snapshot: <path>` line. Parse the JSON separately from that trailer. Require successful execution and JSON `status: ok`, then check every container's `status` and `reason`. Read only the snapshot named by this invocation if a file is needed. For a detected character/pane/container change, retry once within this request; another failure or other reader error stops current reporting. Do not silently substitute old snapshots. Saved snapshots are historical, with their actual capture times; missing timestamps are unknown.

## Coverage and report

- Lead with `captured_at` (UTC), character, source, collection duration and snapshot path. This is a non-atomic observation at that time.
- State that coverage is limited to reachable, explored, unlocked containers already referenced by the active character's loot pane, including restrictions on nested containers. It excludes floor and carried inventory and does not establish a complete kitchen/room/house inventory.
- Group fridge, freezer and cupboard contents by distinct container, retaining type, label, tile and nested item paths. Number same-labelled cupboards separately within this snapshot; tile/name alone is not a unique permanent container identity. Keep other observed cooking containers, such as an oven, separate.
- Include food, drinks, ingredients, cookware and utensils. Non-Food drinks or ingredients must not disappear because `is_food` is false. If other contents are omitted for relevance, say so; a container is empty only when a successfully read `items` list is empty.
- Use `count` for item/package quantities, not portions or volume. Preserve different per-item amounts and states; aggregate only matching items in the same container with identical relevant metadata. Retain item IDs where needed.
- For Food items, report `portion_fraction_remaining` as the derived current/base-hunger fraction: 0.5 means about 50% of that baseline remains, not a measured serving count or original recipe/package fraction. Null means unknown, never full. Report freshness and frozen/freezing/thawing flags, cooking state and dangerous-uncooked flag. Frozen and thawing can both be true. Use `cooking_state` so noncookable food is not called raw. Freshness reflects engine flags even on canned/dried food; exact freshness time is unavailable. Non-Food fluid containers have no measured food portions, freshness or fluid volume.
- `freezing_progress_fraction` is stored progress, not a time estimate. Null freezing/thawing direction means unknown, especially inside a freezer because power is not queried. Never call the game's `isFreezing`, `isThawing` or power-check methods to fill it in; those update a world-object power cache.
- List every unreadable container and reason, with its contents unknown. Requested container types absent from the result are not observed, not empty. Top-level `ok` does not make partial coverage complete. Do not assert recipe eligibility or infer an unmeasured deadline, hidden hazard or food state.

## Observational boundary

Use only the existing one-shot reader. Do not refresh/select/open the loot pane, explore/populate containers, manipulate items, move the character, change pause state, send mouse/keyboard input, edit saves/mods or run arbitrary Lua/JVM commands. No automation, polling, background observer or OMP-as-subagent integration. JVM attachment may cause a brief hitch; the reader detaches and leaves no recurring task.

## Invocation

Codex: `$zomboid-nearby-food List nearby fridge, freezer and cupboard supplies for dinner.`

OMP: `/skill:zomboid-nearby-food List nearby fridge, freezer and cupboard supplies for dinner.`
