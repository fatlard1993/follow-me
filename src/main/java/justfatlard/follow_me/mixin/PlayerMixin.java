package justfatlard.follow_me.mixin;

import justfatlard.follow_me.Tethers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class PlayerMixin {
	/** Sneaking does not climb off a theme ride: /follow stop does. */
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void followMe$stayOnTheRide(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player && Tethers.carried(player)) cir.setReturnValue(false);
	}

	/** Out of the fight while riding along: no mob picks a rider as a target, and any that had one lets go. */
	@Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
	private void followMe$noTarget(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player && Tethers.carried(player)) cir.setReturnValue(false);
	}
}
