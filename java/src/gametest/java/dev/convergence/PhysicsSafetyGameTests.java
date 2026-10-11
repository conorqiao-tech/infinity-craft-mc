package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

public class PhysicsSafetyGameTests {
    @GameTest public void hugeNativeKnockbackNoLongerFreezesZombieMovement(TestContext c) {
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var start = c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2, 5, 2));
            zombie.setPosition(start.getX()+.5, start.getY(), start.getZ()+.5);
            zombie.setAiDisabled(true); zombie.setNoGravity(true);
            zombie.takeKnockback(1e20, 1, 1);
            c.assertTrue(zombie.getVelocity().length() > 1 && zombie.getVelocity().length() < 9, "Huge knockback still launches the mob with a finite native impulse");
            var before = zombie.getEntityPos();
            for (int i=0; i<3; i++) zombie.tick();
            c.assertTrue(zombie.getEntityPos().distanceTo(before) < 100, "The real Zombie tick/collision path returns and remains bounded");
        } finally { zombie.discard(); }
        c.complete();
    }

    @GameTest public void actualPlayerHitKeepsHugeKnockbackAttributeAndBoundsTheTargetMotion(TestContext c) {
        var player = new ModeGameTests().player(c, "physics-hit");
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        OperatorGameTests.level(player, LeveledPermissionPredicate.OWNERS);
        try {
            c.assertTrue(AdminStats.set(player.getCommandSource(),player,"attack_knockback",1e20).success(), "Large saved knockback remains editable");
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
            zombie.setPosition(player.getEntityPos().add(0, 0, 1.5));
            zombie.setAiDisabled(true); zombie.setNoGravity(true);
            c.getWorld().spawnEntity(zombie);
            player.attack(zombie);
            c.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "A real vanilla player attack hits the target");
            c.assertTrue(zombie.getVelocity().length() > 1 && zombie.getVelocity().length() < 9, "The real hit applies bounded knockback");
            c.assertEquals(player.getAttributeValue(EntityAttributes.ATTACK_KNOCKBACK), 1e20, "Gameplay protection does not rewrite the admin's attribute");
            zombie.tick();
        } finally {
            zombie.discard(); OperatorGameTests.deop(player);
            player.getEntityWorld().getServer().getPlayerManager().remove(player);
        }
        c.complete();
    }

    @GameTest public void motionGuardHandlesFiniteExtremesNonFiniteAndDirectCollisionCalls(TestContext c) {
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var start = c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,5,2));
            zombie.setPosition(start.getX()+.5,start.getY(),start.getZ()+.5);
            zombie.setVelocity(new Vec3d(Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE));
            c.assertTrue(zombie.getVelocity().length() <= 32.000001, "Native vector velocity rejects enormous work without overflowing");
            zombie.setVelocity(Double.POSITIVE_INFINITY,0,0);
            c.assertEquals(zombie.getVelocity(),Vec3d.ZERO,"Non-finite double overload becomes stationary");
            var before=zombie.getEntityPos();
            zombie.move(MovementType.SELF,new Vec3d(1e20,1e20,1e20));
            c.assertTrue(zombie.getEntityPos().distanceTo(before)<=32.000001,"Direct native move cannot perform an enormous collision sweep");
            c.assertEquals(PhysicsSafety.motion(new Vec3d(Double.NaN,0,0)),Vec3d.ZERO,"Non-finite movement is stationary");
        } finally { zombie.discard(); }
        c.complete();
    }

    @GameTest public void ordinaryMovementJumpAndKnockbackKeepTheirNativeValues(TestContext c) {
        var zombie=EntityType.ZOMBIE.create(c.getWorld(),SpawnReason.COMMAND);
        try {
            var jump=new Vec3d(.1,15,.2);
            zombie.setVelocity(jump);
            c.assertEquals(zombie.getVelocity(),jump,"Edited native jump within the movement bound remains unchanged");
            var tiny=new Vec3d(Double.MIN_VALUE,0,0);
            c.assertTrue(PhysicsSafety.motion(tiny)==tiny,"Subnormal motion remains unchanged and does not overflow normalization");
            zombie.setVelocity(Vec3d.ZERO);
            zombie.takeKnockback(.4,1,0);
            c.assertTrue(Math.abs(zombie.getVelocity().x+.4)<1e-8,"Ordinary knockback retains its normal strength");
            c.assertEquals(PhysicsSafety.knockback(Double.POSITIVE_INFINITY),0d,"Invalid impulse is stationary");
        } finally { zombie.discard(); }
        c.complete();
    }
}
