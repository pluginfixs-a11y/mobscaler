# MobScaler

Server-side Fabric mod for Minecraft **26.2**. The further you go from world spawn, the stronger normal hostile
mobs become. Vanilla clients need nothing installed.

## What scales

Difficulty is a list of stages, not a straight line: `diff_stages` in the config, `[blocks from spawn, share]`
pairs. Between two stages it interpolates linearly, before the first one nothing scales, from the last one it is
at full strength (20000 blocks by default):

| distance | share | health / damage / drops |
|---|---|---|
| 3000 | 0.00 | ×1.0 / ×1.0 / ×1.0 |
| 8000 | 0.30 | ×1.45 / ×1.30 / ×1.6 |
| 10000 | 0.425 | ×1.64 / ×1.43 / ×1.85 |
| 12000 | 0.55 | ×1.83 / ×1.55 / ×2.1 |
| 16000 | 0.78 | ×2.17 / ×1.78 / ×2.56 |
| 20000 | 1.00 | ×2.5 / ×2.0 / ×3.0 |

At the top stage:

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
amplifier II once the difficulty share passes 0.65 (about 13 700 blocks on the default curve) and 3× duration.
Stray, Bogged, Parched and Wither Skeletons are skipped.

## Phantoms

Vanilla phantom spawning is switched off and replaced by distance rules. Distance is X/Z from world spawn,
the same measure the rest of the mod uses:

| distance from spawn | player has slept | player has not slept |
|---|---|---|
| below 3000 | none | none |
| 3000 – 7000 | none | pack of 1 → 5 |
| 7000 and further | pack of up to 2 | pack of 5 → 18 |

Packs pile up until the player has `phantom_limit_per_player` (18) phantoms around, then that player stops
getting new ones. Everything else stays vanilla: night, open sky, the difficulty roll, the insomnia chance
(only once `time_since_rest` is past 72000 ticks, i.e. three in-game days), and an empty block for the spawn.
Phantom stats are not scaled by the rest of the mod (they stay in `excluded_mobs`), only their numbers change.

Pack size comes from `phantom_packs`, a list of `[distance, pack size]` stages: `[3000, 1]`, `[6000, 4]`,
`[10000, 8]`, `[15000, 13]`, `[20000, 18]`. Between two stages it interpolates linearly, past the last stage it
stays flat.

## World border

`world_border_radius` (20000 by default, `0` leaves the border alone) is applied on every server start, to
**every dimension**: a vanilla border of `2 × radius` side length centred on (0, 0). That is **40000** for the
`/worldborder` command, while players hit the wall 20000 blocks from spawn along the axes. In 26.2 the border is
per-dimension `SavedData` (`ServerLevel#getWorldBorder` → `getDataStorage().computeIfAbsent(WorldBorder.TYPE)`),
so setting the overworld alone would leave the Nether and the End at the vanilla 29 999 984. The mod does it on
start so a fresh or wiped world gets the limit without anyone typing a command.

Vanilla borders are squares: a side of 40000 means 20000 along X/Z but up to 28 284 on the diagonal, and mob
scaling measures a circle, so the diagonal reaches full strength before the wall.

## Skeletons

One random tipped arrow per skeleton, amplifier II once the difficulty share is 0.65 — on the default curve that
is about 13 700 blocks (it used to be 7 500 on the straight line), duration ×3.

## Who is excluded

Raid mobs and everything with special behaviour: pillager, vindicator, evoker, illusioner, ravager, witch, vex,
phantom, wither, ender dragon, warden, elder guardian, shulker, breeze. The list is `excluded_mobs` in the config.
Phantoms are excluded from stat scaling only — their spawning has its own rules above.

## Config

`config/mobscaler.json`, generated on first start. Everything above is a key: `config_version`, `radius_min`,
`radius_max`, `diff_stages`, `world_border_radius`, `max_health_multiplier`, `max_attack_multiplier`,
`max_speed_multiplier`, `max_follow_multiplier`, `max_xp_multiplier`, `armor_slot_chance_min/max`,
`max_drop_multiplier`, `creeper_fuse_min`, `creeper_radius_max`, `tipped_skeleton_arrows`,
`skeleton_effect_duration_multiplier`, `excluded_mobs`, `scale_phantoms`, `phantom_packs`,
`phantom_rested_radius`, `phantom_rested_pack`, `phantom_limit_per_player`, `phantom_limit_radius`.

`diff_stages = []` falls back to the old straight line between `radius_min` and `radius_max`; when `diff_stages` is
set, `radius_max` is ignored (the log says so and warns if the two disagree). A file without `config_version` is
treated as version 1 (a 10k world) and is migrated in place on load: `radius_max` 10000 → 20000, the phantom stages
and `diff_stages` get the new defaults. A custom `radius_min`/`radius_max` is never overwritten — such a config keeps
the straight line on its own numbers, and if `world_border_radius` is then larger than `radius_max` the log warns
that the ring between them is already at full strength. A broken JSON file is copied to `mobscaler.json.bak` before
the defaults replace it.

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`.

## Build

```
./gradlew build
```

## License

MIT.
