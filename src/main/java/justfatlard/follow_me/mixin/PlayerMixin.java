package justfatlard.follow_me.mixin;

import justfatlard.follow_me.Tethers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sneaking does not climb off a theme ride: /follow stop does. */
@Mixin(Player.class)
public class PlayerMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void followMe$stayOnTheRide(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player && Tethers.carried(player)) cir.setReturnValue(false);
	}
}
