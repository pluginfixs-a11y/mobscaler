# MobScaler

Server-side Fabric mod for Minecraft **26.2**. The further you go from world spawn, the stronger normal hostile
mobs become. Vanilla clients need nothing installed.

## What scales

Between `radius_min` (3000 blocks) and `radius_max` (10000) the mod ramps, and past `radius_max` it is at full
strength:

| stat | default at full strength |
|---|---|
| max health | ×2.5 |
| attack damage | ×2.0 |
| movement speed | ×1.25 |
| follow range | ×1.3 |
| XP drop | ×4.0 |
| armour chance per slot | 5 % → 80 % |
| drop amount | ×3.0 |
| creeper fuse / blast radius | 30 → 15 ticks / 3 → 7 |

Armour is picked from six materials with distance-shifted weights, plus a 12 % turtle-helmet chance; armour
never drops. Drops are multiplied only for stackable items, fractional, capped at the max stack size.

Scaling runs once per mob, 50 ms after it loads (so `finalizeSpawn` has already rolled equipment and
variants) and marks it with the `mobscaler:scaled` tag, which is saved with the mob. A scaled mob comes out
at full health.

Skeletons get one random tipped arrow per skeleton (poison, slowness, weakness, mining fatigue, nausea) with
amplifier II past 7500 blocks and 3× duration. Stray, Bogged, Parched and Wither Skeletons are skipped.

## Phantoms

Vanilla phantom spawning is switched off and replaced by distance rules. Distance is X/Z from world spawn,
the same measure the rest of the mod uses:

| distance from spawn | player has slept | player has not slept |
|---|---|---|
| below 3000 | none | none |
| 3000 – 7000 | none | pack of 1 → 10 |
| 7000 and further | pack of up to 2 | pack of 10 → 18 |

Packs pile up until the player has `phantom_limit_per_player` (18) phantoms around, then that player stops
getting new ones. Everything else stays vanilla: night, open sky, the difficulty roll, the insomnia chance
(only once `time_since_rest` is past 72000 ticks, i.e. three in-game days), and an empty block for the spawn.
Phantom stats are not scaled by the rest of the mod (they stay in `excluded_mobs`), only their numbers change.

Pack size comes from `phantom_packs`, a list of `[distance, pack size]` stages: `[3000, 1]`, `[5000, 5]`,
`[7000, 10]`, `[10000, 18]`. Between two stages it interpolates linearly, past the last stage it stays flat.

## Who is excluded

Raid mobs and everything with special behaviour: pillager, vindicator, evoker, illusioner, ravager, witch, vex,
phantom, wither, ender dragon, warden, elder guardian, shulker, breeze. The list is `excluded_mobs` in the config.
Phantoms are excluded from stat scaling only — their spawning has its own rules above.

## Config

`config/mobscaler.json`, generated on first start. Everything above is a key: `radius_min`, `radius_max`,
`max_health_multiplier`, `max_attack_multiplier`, `max_speed_multiplier`, `max_follow_multiplier`,
`max_xp_multiplier`, `armor_slot_chance_min/max`, `max_drop_multiplier`, `creeper_fuse_min`,
`creeper_radius_max`, `tipped_skeleton_arrows`, `skeleton_effect_duration_multiplier`, `excluded_mobs`,
`scale_phantoms`, `phantom_packs`, `phantom_rested_radius`, `phantom_rested_pack`, `phantom_limit_per_player`,
`phantom_limit_radius`.

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`.

## Build

```
./gradlew build
```

## License

MIT.
