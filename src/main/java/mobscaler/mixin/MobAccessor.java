package mobscaler.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("xpReward")
	int getXpReward();

	@Accessor("xpReward")
	void setXpReward(int xp);
}
