package dev.convergence.destinationperf;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native headless server otherwise burns tick deadlines before workers can generate cold terrain. */
@Mixin(GameTestServer.class)
abstract class GameTestPacingMixin {
    @Inject(method="waitUntilNextTick",at=@At("RETURN"))
    private void paceColdFixtures(CallbackInfo callback) {
        var server=(GameTestServer)(Object)this;
        if(!server.isRunning()||!Boolean.getBoolean("infinity.gametest.paced"))return;
        long until=System.nanoTime()+50_000_000L;
        while(server.isRunning()&&!Thread.currentThread().isInterrupted()) {
            long remaining=until-System.nanoTime();if(remaining<=0)return;
            LockSupport.parkNanos(remaining);
        }
    }
}
