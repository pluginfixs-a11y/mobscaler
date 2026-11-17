package mobscaler.mixin;

import mobscaler.MobScaler;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.entity.monster.skeleton.Parched;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Стрелы усиленных скелетов.
 * <p>
 * Эффект вешается прямо на выпущенную стрелу через {@code Arrow.addEffect}, поэтому в руке
 * у скелета ничего не висит. Каждый скелет получает ровно один эффект (выбранный при спавне),
 * а не все сразу. Зимний/болотный/иссохший/визер-скелеты не трогаются — у них уже есть
 * собственный фирменный эффект стрелы.
 */
@Mixin(AbstractSkeleton.class)
public abstract class SkeletonArrowMixin {

	@Inject(method = "getArrow(Lnet/minecraft/world/item/ItemStack;FLnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;", at = @At("RETURN"))
	private void mobscaler$tipArrow(ItemStack arrowStack, float velocity, ItemStack weaponStack, CallbackInfoReturnable<AbstractArrow> cir) {
		if (!(cir.getReturnValue() instanceof Arrow arrow)) {
			return;
		}

		AbstractSkeleton self = (AbstractSkeleton) (Object) this;
		if (!self.entityTags().contains(MobScaler.TAG_SCALED)) {
			return;
		}
		if (self instanceof Stray || self instanceof Bogged || self instanceof Parched || self instanceof WitherSkeleton) {
			return;
		}

		MobEffectInstance effect = MobScaler.skeletonArrowEffect(self);
		if (effect != null) {
			arrow.addEffect(effect);
		}
	}
}
