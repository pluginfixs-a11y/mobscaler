package mobscaler.mixin;

import mobscaler.MobScaler;
import mobscaler.PhantomSpawn;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ванильный спавн фантомов заменён своим.
 * <p>
 * Ваниль спавнит 1–4 фантома рядом с любым игроком, который давно не спал, в любом месте карты.
 * У мода правила другие (близко к центру их нет вовсе, дальше — пачки по расстоянию), поэтому
 * {@code tick} целиком отменяется и вызывается {@link PhantomSpawn#tick}. Геймрул
 * {@code doPhantomSpawning} по-прежнему уважается: выключили его — фантомов нет.
 */
@Mixin(PhantomSpawner.class)
public class PhantomSpawnerMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void mobscaler$tick(ServerLevel level, boolean spawnEnemies, CallbackInfo ci) {
		if (!MobScaler.config.scale_phantoms) {
			return;
		}

		ci.cancel();
		if (!spawnEnemies) {
			return;
		}
		if (!Boolean.TRUE.equals(level.getGameRules().get(GameRules.SPAWN_PHANTOMS))) {
			return;
		}
		PhantomSpawn.tick(level, (PhantomSpawner) (Object) this);
	}
}
