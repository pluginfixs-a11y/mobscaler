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

Skeletons get one random tipped arrow per skeleton (poison, slowness, weakness, mining fatigue, nausea) with
amplifier II past 7500 blocks and 3× duration. Stray, Bogged, Parched and Wither Skeletons are skipped.

## Who is excluded

Raid mobs and everything with special behaviour: pillager, vindicator, evoker, illusioner, ravager, witch, vex,
phantom, wither, ender dragon, warden, elder guardian, shulker, breeze. The list is `excluded_mobs` in the config.

## Config

`config/mobscaler.json`, generated on first start. Everything above is a key: `radius_min`, `radius_max`,
`max_health_multiplier`, `max_attack_multiplier`, `max_speed_multiplier`, `max_follow_multiplier`,
`max_xp_multiplier`, `armor_slot_chance_min/max`, `max_drop_multiplier`, `creeper_fuse_min`,
`creeper_radius_max`, `tipped_skeleton_arrows`, `skeleton_effect_duration_multiplier`, `excluded_mobs`.

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`.

## Build

```
./gradlew build
```

## License

MIT.
