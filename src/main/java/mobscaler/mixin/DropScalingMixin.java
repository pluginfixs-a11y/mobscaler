package mobscaler.mixin;

import mobscaler.MobScaler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Множитель дропа для усиленных мобов.
 * <p>
 * Висит на самом нижнем перегрузе {@code spawnAtLocation(level, stack, Vec3)} — через него проходят
 * и обычный лут из таблицы, и дроп из инвентаря, поэтому множитель применяется ровно один раз.
 * Множим только стакающиеся предметы: шмотки, оружие и книги с {@code maxStackSize = 1} не трогаем,
 * а количество считаем дробно (остаток — вероятность), чтобы не было «вдруг 64 штуки».
 */
@Mixin(Entity.class)
public abstract class DropScalingMixin {

	@Inject(method = "spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"))
	private void mobscaler$scaleDrops(ServerLevel level, ItemStack stack, Vec3 offset, CallbackInfoReturnable<ItemEntity> cir) {
		if (!((Object) this instanceof LivingEntity entity)) {
			return;
		}
		if (!entity.entityTags().contains(MobScaler.TAG_SCALED)) {
			return;
		}
		if (stack.isEmpty()) {
			return;
		}

		int maxStackSize = stack.getMaxStackSize();
		if (maxStackSize <= 1) {
			return;
		}

		double multiplier = MobScaler.dropMultiplier(Math.hypot(entity.getX(), entity.getZ()));
		if (multiplier <= 1.0) {
			return;
		}

		int count = stack.getCount();
		double exact = count * multiplier;
		int scaled = (int) Math.floor(exact);
		if (ThreadLocalRandom.current().nextDouble() < exact - scaled) {
			scaled++;
		}
		scaled = Math.min(scaled, maxStackSize);

		if (scaled > count) {
			stack.setCount(scaled);
		}
	}
}
