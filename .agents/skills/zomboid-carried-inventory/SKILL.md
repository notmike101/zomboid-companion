---
name: zomboid-carried-inventory
description: Use when inspecting the active Project Zomboid character's carried inventory, nested bags, equipment, remaining food fractions and freshness or cooking/freezing states through the existing live reader.
---

# Carried inventory

Read the active single-player character's visible carried items with the existing reader. Keep this feature separate from nearby storage.

## Read

Run once in PowerShell, using the host's command tool:

```powershell
& D:\zomboid\read-inventory.ps1
```

For a new PowerShell process, use `pwsh -NoProfile -File D:\zomboid\read-inventory.ps1`. The launcher requires the running game at its configured installation and the built reader JAR. Read [the project README](../../../README.md) for build requirements and compatibility limits; report a missing dependency instead of installing, rebuilding or changing game configuration during an inventory request.

The command prints JSON followed by a `Snapshot: <path>` line. Parse the JSON separately from that trailer. Require both successful command execution and JSON `status: ok` before reporting a current result. Read only the snapshot named by this invocation if a file is needed; do not select an older file by filename or directory order.

If a detected inventory/character/equipment change invalidates the read, retry once on demand within this request. If it fails again or another error occurs, report the error and stop. Do not silently substitute a previous snapshot. When asked to inspect a saved snapshot, label it historical and retain its capture time; missing timestamps are unknown.

## Report

- Lead with `captured_at` (UTC), character, `source`, collection duration, and the snapshot path. This is a non-atomic observation at that time; values can change while the user plays.
- Group items by the full `container` path, retaining nested bags and equipment stored outside the root inventory. Include names, quantities from `count`, equipment slots, and durability where available. Count an equipped item once even if it occupies multiple slots.
- Sum `count` only for matching items in the same container with identical relevant metadata, including remaining food fraction, freshness, freezing/thawing and cooking states. Keep differing per-item amounts/statuses separate, retaining item IDs where needed. `item_records` is the number of rows, not necessarily the total quantity.
- For Food items, report `portion_fraction_remaining` as a derived fraction of base hunger (for example, 0.5 means about 50% of that baseline remains), never a measured serving count or fraction of the original recipe/package. A null fraction is unknown, not full. Item counts do not measure portions, medication doses or fluid volumes.
- Include the reported freshness, frozen/freezing/thawing flags, cooking state and dangerous-uncooked flag. A food can remain flagged frozen while thawing. Use `cooking_state` to distinguish raw cookable food from food that is not cookable. Freshness is the game's state, including for canned/dried food; it is not an independent shelf-life estimate. Exact freshness time is unavailable. Non-Food fluid containers have no measured food freshness, portions or liquid volume.
- `freezing_progress_fraction` is stored progress, not a time estimate. Direction is derived without querying power: null freezing/thawing means unknown, especially inside a freezer. Never call the game's `isFreezing`, `isThawing` or power-check methods to fill it in; those update a world-object power cache.
- Preserve distinctions between same-named bags using their supplied paths/identifiers. Mark missing fields unknown. Explain detected incomplete reads instead of calling them complete.
- Excluded hidden objects are internal display models; their exclusion is not evidence about character health.

## Observational boundary

Use only the existing one-shot reader. Do not manipulate items, move the character, change pause state, send mouse/keyboard input, inspect hidden gameplay state, edit saves/mods, or run arbitrary Lua/JVM commands. No automation, polling, background observer or OMP-as-subagent integration. JVM attachment may cause a brief hitch; the reader detaches and leaves no recurring task.

## Invocation

Codex: `$zomboid-carried-inventory List everything I am carrying, including bags and equipment.`

OMP: `/skill:zomboid-carried-inventory List everything I am carrying, including bags and equipment.`
