package dinner.dev.lumispheric.mixin;

import dinner.dev.lumispheric.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
abstract class LightEngineMixin {
    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Shadow
    public abstract int getLightValue(BlockPos pos);

    @Inject(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void lumispheric$readLightWithinPackedYRange(
        BlockPos pos, CallbackInfoReturnable<Integer> callback
    ) {
        if (!Config.enabled
            || !(this.chunkSource.getLevel() instanceof ClientLevel level)
            || level.dimension() != Level.OVERWORLD) {
            return;
        }

        int y = pos.getY();
        if (y < -2048 || y > 2047) {
            BlockPos supportedPos = new BlockPos(pos.getX(), Math.max(-2048, Math.min(2047, y)), pos.getZ());
            callback.setReturnValue(this.getLightValue(supportedPos));
        }
    }
}
