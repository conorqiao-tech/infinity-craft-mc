package dev.convergence.vitalsperf;

import dev.convergence.ClientVitals;
import dev.convergence.ClientVitalsPerformanceProbe;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientVitals.class)
abstract class ClientVitalsProbeMixin {
    @Redirect(method="rewrite", at=@At(value="NEW", target="()Ljava/util/ArrayList;"))
    private static ArrayList<?> emptyList() {
        ClientVitalsPerformanceProbe.list();
        return new ArrayList<>();
    }
    @Redirect(method="rewrite", at=@At(value="NEW", target="(Ljava/util/Collection;)Ljava/util/ArrayList;"))
    private static ArrayList<?> copiedList(Collection<?> original) {
        ClientVitalsPerformanceProbe.list();
        return new ArrayList<>(original);
    }
    @Redirect(method="rewrite", at=@At(value="NEW", target="(ILjava/util/Collection;)Lnet/minecraft/network/protocol/game/ClientboundUpdateAttributesPacket;"))
    private static ClientboundUpdateAttributesPacket copiedAttributes(int id, Collection<AttributeInstance> original) {
        ClientVitalsPerformanceProbe.attributes();
        return new ClientboundUpdateAttributesPacket(id, original);
    }
    @Redirect(method="rewrite", at=@At(value="INVOKE", target="Lnet/minecraft/network/syncher/SynchedEntityData$DataValue;create(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)Lnet/minecraft/network/syncher/SynchedEntityData$DataValue;"))
    private static <T> SynchedEntityData.DataValue<T> copiedMetadata(EntityDataAccessor<T> accessor, T value) {
        ClientVitalsPerformanceProbe.metadata();
        return SynchedEntityData.DataValue.create(accessor, value);
    }
}
