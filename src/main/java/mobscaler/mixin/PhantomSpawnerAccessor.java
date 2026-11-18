package mobscaler.mixin;

import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к таймеру ванильного спавнера: свой спавн идёт с той же периодичностью. */
@Mixin(PhantomSpawner.class)
public interface PhantomSpawnerAccessor {

	@Accessor("nextTick")
	int mobscaler$getNextTick();

	@Accessor("nextTick")
	void mobscaler$setNextTick(int value);
}
