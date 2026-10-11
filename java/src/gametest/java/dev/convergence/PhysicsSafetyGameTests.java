package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public class PhysicsSafetyGameTests {
    @GameTest public void hugeNativeKnockbackNoLongerFreezesZombieMovement(GameTestHelper c) {
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var start = c.absolutePos(new net.minecraft.core.BlockPos(2, 5, 2));
            zombie.setPos(start.getX()+.5, start.getY(), start.getZ()+.5);
            zombie.setNoAi(true); zombie.setNoGravity(true);
            zombie.knockback(1e20, 1, 1, zombie.damageSources().generic(), 0);
            c.assertTrue(zombie.getDeltaMovement().length() > 1 && zombie.getDeltaMovement().length() < 9, "Huge knockback still launches the mob with a finite native impulse");
            var before = zombie.position();
            for (int i=0; i<3; i++) zombie.tick();
            c.assertTrue(zombie.position().distanceTo(before) < 100, "The real Zombie tick/collision path returns and remains bounded");
        } finally { zombie.discard(); }
        c.succeed();
    }

    @GameTest public void actualPlayerHitKeepsHugeKnockbackAttributeAndBoundsTheTargetMotion(GameTestHelper c) {
        var player = new ModeGameTests().player(c, "physics-hit");
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
        try {
            c.assertTrue(AdminStats.set(player.createCommandSourceStack(),player,"attack_knockback",1e20).success(), "Large saved knockback remains editable");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            zombie.setPos(player.position().add(0, 0, 1.5));
            zombie.setNoAi(true); zombie.setNoGravity(true);
            c.getLevel().addFreshEntity(zombie);
            player.attack(zombie);
            c.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "A real vanilla player attack hits the target");
            c.assertTrue(zombie.getDeltaMovement().length() > 1 && zombie.getDeltaMovement().length() < 9, "The real hit applies bounded knockback");
            c.assertValueEqual(player.getAttributeValue(Attributes.ATTACK_KNOCKBACK), 1e20, "Gameplay protection does not rewrite the admin's attribute");
            zombie.tick();
        } finally {
            zombie.discard(); OperatorGameTests.deop(player);
            player.level().getServer().getPlayerList().remove(player);
        }
        c.succeed();
    }

    @GameTest public void motionGuardHandlesFiniteExtremesNonFiniteAndDirectCollisionCalls(GameTestHelper c) {
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var start = c.absolutePos(new net.minecraft.core.BlockPos(2,5,2));
            zombie.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            zombie.setDeltaMovement(new Vec3(Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE));
            c.assertTrue(zombie.getDeltaMovement().length() <= 32.000001, "Native vector velocity rejects enormous work without overflowing");
            zombie.setDeltaMovement(Double.POSITIVE_INFINITY,0,0);
            c.assertValueEqual(zombie.getDeltaMovement(),Vec3.ZERO,"Non-finite double overload becomes stationary");
            var before=zombie.position();
            zombie.move(MoverType.SELF,new Vec3(1e20,1e20,1e20));
            c.assertTrue(zombie.position().distanceTo(before)<=32.000001,"Direct native move cannot perform an enormous collision sweep");
            c.assertValueEqual(PhysicsSafety.motion(new Vec3(Double.NaN,0,0)),Vec3.ZERO,"Non-finite movement is stationary");
        } finally { zombie.discard(); }
        c.succeed();
    }

    @GameTest public void ordinaryMovementJumpAndKnockbackKeepTheirNativeValues(GameTestHelper c) {
        var zombie=EntityTypes.ZOMBIE.create(c.getLevel(),EntitySpawnReason.COMMAND);
        try {
            var jump=new Vec3(.1,15,.2);
            zombie.setDeltaMovement(jump);
            c.assertValueEqual(zombie.getDeltaMovement(),jump,"Edited native jump within the movement bound remains unchanged");
            var tiny=new Vec3(Double.MIN_VALUE,0,0);
            c.assertTrue(PhysicsSafety.motion(tiny)==tiny,"Subnormal motion remains unchanged and does not overflow normalization");
            zombie.setDeltaMovement(Vec3.ZERO);
            zombie.knockback(.4,1,0,zombie.damageSources().generic(),0);
            c.assertTrue(Math.abs(zombie.getDeltaMovement().x+.4)<1e-8,"Ordinary knockback retains its normal strength");
            c.assertValueEqual(PhysicsSafety.knockback(Double.POSITIVE_INFINITY),0d,"Invalid impulse is stationary");
        } finally { zombie.discard(); }
        c.succeed();
    }
}
