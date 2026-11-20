package mobscaler;

import mobscaler.mixin.CreeperAccessor;
import mobscaler.mixin.MobAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Чем дальше от центра мира (0, 0), тем злее обычные враждебные мобы.
 * <p>
 * Логика: на загрузке моба считается дистанция по X/Z. Всё, что ближе первой ступени
 * {@code diff_stages} (по умолчанию 3000), не трогаем вообще. Дальше по ступеням растут здоровье,
 * урон, скорость, дальность агро, опыт, шанс брони и количество дропа; на последней ступени
 * (по умолчанию 20000) — потолок. Боссы, рейдовые и «особые» мобы из списка {@code excluded_mobs}
 * не усиливаются никогда. Там же ставится граница мира {@code world_border_radius}.
 */
public class MobScaler implements ModInitializer {
	public static final String MOD_ID = "mobscaler";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Метка уже обработанного моба — чтобы не усиливать его повторно при перезагрузке чанка. */
	public static final String TAG_SCALED = "mobscaler:scaled";
	/** Метка выбранного эффекта стрелы: {@code mobscaler:arrow:poison} и т.п. */
	public static final String TAG_ARROW = "mobscaler:arrow:";

	/** Пул эффектов, из которого скелет получает ровно один — свой. */
	private static final List<String> ARROW_EFFECTS = List.of(
			"poison", "slowness", "weakness", "mining_fatigue", "nausea");

	private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	public static ModConfig config;

	@Override
	public void onInitialize() {
		config = ModConfig.load();
		LOGGER.info("MobScaler: усиление мобов от {} блоков от центра, потолок на {} (ступеней {}), исключено {} мобов",
				config.radius_min, (int) config.rampEnd(),
				config.diff_stages == null ? 0 : config.diff_stages.size(), config.excluded_mobs.size());
		if (config.diff_stages != null && !config.diff_stages.isEmpty() && config.rampEnd() != config.radius_max) {
			LOGGER.warn("MobScaler: radius_max={} не используется — кривая задана ступенями до {}",
					config.radius_max, (int) config.rampEnd());
		}
		StringBuilder packs = new StringBuilder();
		for (int[] step : config.phantom_packs) {
			if (step == null || step.length < 2) {
				continue;
			}
			if (packs.length() > 0) {
				packs.append(", ");
			}
			packs.append(step[0]).append(" -> ").append(config.phantomPackSize(step[0]));
		}
		LOGGER.info("MobScaler: фантомы — {}; после сна с {} по {}; потолок {} на игрока",
				packs, config.phantom_rested_radius, config.phantom_rested_pack, config.phantom_limit_per_player);
		ServerEntityEvents.ENTITY_LOAD.register(MobScaler::onEntityLoad);
		ServerLifecycleEvents.SERVER_STARTED.register(MobScaler::applyWorldBorder);

		if (System.getProperty("mobscaler.selftest") != null) {
			ServerTickEvents.START_SERVER_TICK.register(SelfTest::onTick);
			LOGGER.info("MobScaler: включена самопроверка (-Dmobscaler.selftest)");
		}
	}

	/**
	 * Граница мира — это и есть лимит «дальше не уходят». Ставится на каждом старте сервера,
	 * поэтому работает и в новом мире, и в одиночке; {@code world_border_radius = 0} её не трогает.
	 * <p>
	 * В 26.2 граница хранится в SavedData <b>каждого уровня</b> (`ServerLevel#getWorldBorder` →
	 * `getDataStorage().computeIfAbsent(WorldBorder.TYPE)`), поэтому обходим все измерения: иначе
	 * в Нижнем и Энде осталась бы ванильная почти бесконечная граница.
	 */
	public static void applyWorldBorder(MinecraftServer server) {
		int radius = config.world_border_radius;
		if (radius <= 0) {
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			WorldBorder border = level.getWorldBorder();
			border.setCenter(0.0, 0.0);
			border.setSize(2.0 * radius);
		}
		LOGGER.info("MobScaler: граница мира {} блоков от центра (сторона {}) во всех измерениях",
				radius, 2.0 * radius);
	}

	public static void onEntityLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof Monster monster)) {
			return;
		}
		if (level.dimension() != Level.OVERWORLD) {
			return;
		}
		if (monster.entityTags().contains(TAG_SCALED)) {
			return;
		}
		if (config.isExcluded(monster.getType())) {
			return;
		}

		// Даём игре 50 мс на finalizeSpawn (экипировка, варианты моба) и только потом считаем статы.
		CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
			// сервер мог уже остановиться: getServer() тогда null, и задача молча пропадает
			MinecraftServer server = level.getServer();
			if (server == null) {
				return;
			}
			server.execute(() -> {
				if (monster.isRemoved() || !monster.isAlive()) {
					return;
				}
				if (monster.entityTags().contains(TAG_SCALED)) {
					return;
				}
				scale(monster);
			});
		});
	}

	private static void scale(Monster monster) {
		double distance = Math.hypot(monster.getX(), monster.getZ());
		double diff = config.diffFactor(distance);
		if (diff <= 0.0) {
			return;
		}
		monster.addTag(TAG_SCALED);

		scaleAttribute(monster, Attributes.MAX_HEALTH, 1.0 + (config.max_health_multiplier - 1.0) * diff);
		monster.setHealth(monster.getMaxHealth());
		scaleAttribute(monster, Attributes.ATTACK_DAMAGE, 1.0 + (config.max_attack_multiplier - 1.0) * diff);
		scaleAttribute(monster, Attributes.MOVEMENT_SPEED, 1.0 + (config.max_speed_multiplier - 1.0) * diff);
		scaleAttribute(monster, Attributes.FOLLOW_RANGE, 1.0 + (config.max_follow_multiplier - 1.0) * diff);

		MobAccessor xp = (MobAccessor) monster;
		int baseXp = xp.getXpReward();
		xp.setXpReward((int) Math.round(baseXp * (1.0 + (config.max_xp_multiplier - 1.0) * diff)));

		if (config.scale_creepers && monster instanceof Creeper creeper) {
			CreeperAccessor accessor = (CreeperAccessor) creeper;
			accessor.setMaxFuseTime((int) Math.round(30.0 - (30.0 - config.creeper_fuse_min) * diff));
			accessor.setExplosionRadius((int) Math.round(3.0 + (config.creeper_radius_max - 3.0) * diff));
		}

		if (config.tipped_skeleton_arrows && monster instanceof AbstractSkeleton) {
			monster.addTag(TAG_ARROW + ARROW_EFFECTS.get(ThreadLocalRandom.current().nextInt(ARROW_EFFECTS.size())));
		}

		if (config.equip_armor && wearsArmor(monster)) {
			equipArmor(monster, diff);
		}
	}

	private static void scaleAttribute(Monster monster, Holder<Attribute> attribute, double factor) {
		AttributeInstance instance = monster.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		instance.setBaseValue(instance.getBaseValue() * factor);
	}

	/** Броню получают только зомби (включая утопленников и хасков) и скелеты. */
	private static boolean wearsArmor(Monster monster) {
		if (monster instanceof Zombie) {
			return !(monster instanceof ZombieVillager) && !(monster instanceof ZombifiedPiglin);
		}
		return monster instanceof AbstractSkeleton;
	}

	/**
	 * Сначала полностью раздеваем моба от ванильного шмота, потом независимо одеваем каждый слот:
	 * материал выбирается по весам, которые сдвигаются к более крепкому по мере удаления от спавна.
	 */
	private static void equipArmor(Monster monster, double diff) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			monster.setItemSlot(slot, ItemStack.EMPTY);
			monster.setDropChance(slot, 0.0f);
		}

		ThreadLocalRandom rng = ThreadLocalRandom.current();
		double slotChance = config.armor_slot_chance_min
				+ (config.armor_slot_chance_max - config.armor_slot_chance_min) * diff;

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (rng.nextDouble() >= slotChance) {
				continue;
			}

			Item piece;
			if (slot == EquipmentSlot.HEAD && rng.nextDouble() < 0.12) {
				piece = Items.TURTLE_HELMET;
			} else {
				piece = randomPiece(slot, diff, rng);
			}

			monster.setItemSlot(slot, new ItemStack(piece));
			monster.setDropChance(slot, 0.0f);
		}
	}

	/**
	 * Веса материалов брони. На {@code radius_min} почти всё — кожа и кольчуга,
	 * на {@code radius_max} — железо, алмаз и редкий незерит, но кожа/кольчуга/золото
	 * не исчезают совсем, поэтому набор остаётся разнообразным.
	 */
	private static double materialWeight(int material, double diff) {
		return switch (material) {
			case 0 -> 1.00 - 0.90 * diff;   // кожа
			case 1 -> 0.70;                 // кольчуга
			case 2 -> 0.55;                 // золото
			case 3 -> 0.25 + 1.00 * diff;   // железо
			case 4 -> 0.05 + 0.85 * diff;   // алмаз
			default -> 0.45 * diff * diff;  // незерит
		};
	}

	private static Item randomPiece(EquipmentSlot slot, double diff, ThreadLocalRandom rng) {
		double total = 0.0;
		for (int material = 0; material < 6; material++) {
			total += materialWeight(material, diff);
		}

		double roll = rng.nextDouble() * total;
		for (int material = 0; material < 6; material++) {
			roll -= materialWeight(material, diff);
			if (roll <= 0.0) {
				return piece(material, slot);
			}
		}
		return piece(3, slot);
	}

	private static Item piece(int material, EquipmentSlot slot) {
		return switch (material) {
			case 0 -> switch (slot) {
				case HEAD -> Items.LEATHER_HELMET;
				case CHEST -> Items.LEATHER_CHESTPLATE;
				case LEGS -> Items.LEATHER_LEGGINGS;
				default -> Items.LEATHER_BOOTS;
			};
			case 1 -> switch (slot) {
				case HEAD -> Items.CHAINMAIL_HELMET;
				case CHEST -> Items.CHAINMAIL_CHESTPLATE;
				case LEGS -> Items.CHAINMAIL_LEGGINGS;
				default -> Items.CHAINMAIL_BOOTS;
			};
			case 2 -> switch (slot) {
				case HEAD -> Items.GOLDEN_HELMET;
				case CHEST -> Items.GOLDEN_CHESTPLATE;
				case LEGS -> Items.GOLDEN_LEGGINGS;
				default -> Items.GOLDEN_BOOTS;
			};
			case 3 -> switch (slot) {
				case HEAD -> Items.IRON_HELMET;
				case CHEST -> Items.IRON_CHESTPLATE;
				case LEGS -> Items.IRON_LEGGINGS;
				default -> Items.IRON_BOOTS;
			};
			case 4 -> switch (slot) {
				case HEAD -> Items.DIAMOND_HELMET;
				case CHEST -> Items.DIAMOND_CHESTPLATE;
				case LEGS -> Items.DIAMOND_LEGGINGS;
				default -> Items.DIAMOND_BOOTS;
			};
			default -> switch (slot) {
				case HEAD -> Items.NETHERITE_HELMET;
				case CHEST -> Items.NETHERITE_CHESTPLATE;
				case LEGS -> Items.NETHERITE_LEGGINGS;
				default -> Items.NETHERITE_BOOTS;
			};
		};
	}

	/** Множитель количества дропа: 1.0 вблизи, {@code max_drop_multiplier} на дальней дистанции. */
	public static double dropMultiplier(double distance) {
		return 1.0 + (config.max_drop_multiplier - 1.0) * config.diffFactor(distance);
	}

	/**
	 * Эффект для стрелы конкретного скелета: ровно один, свой, выбранный при спавне.
	 * Возвращает {@code null}, если моб не усилен или эффект ему не назначен.
	 */
	public static MobEffectInstance skeletonArrowEffect(AbstractSkeleton skeleton) {
		String name = null;
		for (String tag : skeleton.entityTags()) {
			if (tag.startsWith(TAG_ARROW)) {
				name = tag.substring(TAG_ARROW.length());
				break;
			}
		}
		if (name == null) {
			return null;
		}

		double diff = config.diffFactor(Math.hypot(skeleton.getX(), skeleton.getZ()));
		int seconds = (int) Math.round((6.0 + 18.0 * diff) * config.skeleton_effect_duration_multiplier);
		int amplifier = diff >= 0.65 ? 1 : 0;
		return new MobEffectInstance(effectByName(name), Math.max(20, seconds * 20), amplifier);
	}

	private static Holder<MobEffect> effectByName(String name) {
		return switch (name) {
			case "poison" -> MobEffects.POISON;
			case "slowness" -> MobEffects.SLOWNESS;
			case "weakness" -> MobEffects.WEAKNESS;
			case "mining_fatigue" -> MobEffects.MINING_FATIGUE;
			default -> MobEffects.NAUSEA;
		};
	}
}
