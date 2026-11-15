package justfatlard.follow_me.mixin;

import justfatlard.follow_me.Tethers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A rider passes over items, arrows, tridents and experience, and leaves them for whoever is walking. */
@Mixin({ItemEntity.class, AbstractArrow.class, ThrownTrident.class, ExperienceOrb.class})
public class PickupMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void followMe$handsOff(Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer rider && Tethers.carried(rider)) ci.cancel();
	}
}
