package dev.convergence;

import net.minecraft.world.entity.EntityTypes;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Actual golem movement, vanilla mob weapons, spear charging and native glide physics. */
public class AgentTacticsGameTests {
    static final class Arena implements AutoCloseable {
        private static long ticketSequence=1200;
        final net.minecraft.server.level.TicketType ticket=new net.minecraft.server.level.TicketType(++ticketSequence,net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final java.util.List<net.minecraft.world.level.ChunkPos> chunks=new java.util.ArrayList<>();
        final GameTestHelper context;
        final ServerPlayer owner;
        final AgentCompanions helpers;
        final IronGolem golem;
        final Zombie target;
        final Vec3 start;
        Arena(GameTestHelper context, String name) {
            this.context = context;
            owner = new ModeGameTests().player(context, name);
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SURVIVAL);
            // The declared structure reserves the complete arena from other tests.
            BlockPos feet = context.absolutePos(new BlockPos(20, 55, 20));
            for (BlockPos at : BlockPos.betweenClosed(feet.offset(-9, -1, -9), feet.offset(9, 8, 9)))
                context.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            // Give each fixture its own temporary simulation tickets, including idle reset.
            // Native tests run with embedded clients instead of ordinary player tickets.
            // Use
            // distinct, non-persisted tickets so every participating entity really ticks.
            for(int x=(feet.getX()-9)>>4;x<=(feet.getX()+9)>>4;x++)for(int z=(feet.getZ()-9)>>4;z<=(feet.getZ()+9)>>4;z++) {
                var chunk=new net.minecraft.world.level.ChunkPos(x,z);chunks.add(chunk);
                context.getLevel().getChunkSource().addTicketWithRadius(ticket,chunk,2);
            }
            start = Vec3.atBottomCenterOf(feet);
            owner.setPos(start.add(-5, 0, -5));
            helpers = AgentCompanions.get(context.getLevel().getServer());
            golem = add("alpha", start);
            target = EntityTypes.HUSK.create(context.getLevel(), EntitySpawnReason.COMMAND);
            target.setNoAi(true); target.setPersistenceRequired();
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
            target.setHealth(500); target.setPos(start.add(5, 0, 0)); target.setOnGround(true);
            context.getLevel().addFreshEntity(target);
        }
        IronGolem add(String name, Vec3 point) {
            helpers.spawn(owner, name);
            var record = helpers.owned(owner, name);
            if (record == null) throw new IllegalStateException("No fixture helper");
            var result = helpers.loaded.get(UUID.fromString(record.getKey()));
            helpers.profile(owner, name, AgentCompanions.Profile.ULTIMATE_FINALS);
            result.setPos(point); result.setOnGround(true); result.setDeltaMovement(Vec3.ZERO);
            return result;
        }
        AgentCompanions.Agent record(IronGolem helper) { return helpers.data.agents.get(helper.getStringUUID()); }
        boolean launch(int tick) {
            golem.setTarget(target);
            return helpers.startAerial(golem, record(golem), owner, target, tick);
        }
        public void close() {
            helpers.ceasefire(owner); target.discard();
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            var names = helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID())).map(AgentCompanions.Agent::name).toList();
            for (String name : names) helpers.dismiss(owner, name);
            OperatorGameTests.deop(owner); helpers.server.getPlayerList().remove(owner);
            for(var chunk:chunks)context.getLevel().getChunkSource().removeTicketWithRadius(ticket,chunk,2);
        }
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=60) public void ultimateLeapActuallyMovesThenUsesVanillaMelee(GameTestHelper c) {
        var f = new Arena(c, "tactic-air");
        try {
            c.assertTrue(f.launch(f.helpers.server.getTickCount()), "Clear ground launches a physical golem leap");
            c.assertTrue(f.golem.getDeltaMovement().y > 0 && !f.golem.isNoGravity(), "Launch uses upward velocity with ordinary gravity");
            c.runAfterDelay(5, () -> {
                try {
                    c.assertTrue(f.golem.getY() > f.start.y + .3, "Real entity ticks raise the golem above the ground");
                    c.assertTrue(f.golem.getX() > f.start.x + .4, "Real entity ticks pursue the landing point");
                    c.assertTrue(f.golem.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA),"Aerial helper has a real vanilla glider equipped");
                    c.assertTrue(f.golem.isFallFlying() || f.golem.getFallFlyingTicks()>0,"Real LivingEntity glide state runs during the arc");
                    c.runAfterDelay(25, () -> {
                        try {
                            c.assertTrue(f.target.getHealth() < 500, "Landing closes to native melee range and causes a real golem hit");
                            c.assertTrue(f.helpers.lastAttack.containsKey(f.golem.getUUID()), "Damage follows the helper's recorded native attack path");
                            c.assertTrue(f.target.getHealth() > 350, "The sequence never uses Infinity player weapon burst damage");
                            c.assertTrue(f.golem.getY() < f.start.y + .3, "The helper descends and lands rather than hovering");
                            c.succeed();
                        } finally { f.close(); }
                    });
                } catch (Throwable failure) { f.close(); throw failure; }
            });
        } catch (Throwable failure) { f.close(); throw failure; }
    }

    @GameTest(structure="convergence_tests:combat_arena") public void ceilingsAndWallsRejectLeapsAndKeepGroundPursuit(GameTestHelper c) {
        try (var f = new Arena(c, "tactic-obstacle")) {
            // Follow mode senses hostiles within ten blocks of the owner, not the helper.
            f.owner.setPos(f.start.add(0, 0, -4));
            BlockPos roof = BlockPos.containing(f.start).above(3);
            for (BlockPos at : BlockPos.betweenClosed(roof.offset(-1, 0, -1), roof.offset(6, 0, 1))) c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            c.assertFalse(f.launch(80), "Low roof prevents the full-height leap corridor");
            f.helpers.control(f.golem, f.record(f.golem), 80);
            c.assertTrue(f.golem.getTarget() == f.target, "The nearby hostile remains the ground pursuit target");
            c.assertTrue(f.golem.isUsingItem(), "Blocked aerial route begins the native spear windup before ground pursuit");
            c.assertTrue(f.golem.getDeltaMovement().y <= 0, "Rejected leap adds no upward motion");
            for (BlockPos at : BlockPos.betweenClosed(roof.offset(-1, 0, -1), roof.offset(6, 0, 1))) c.getLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            BlockPos wall = BlockPos.containing(f.start).east(2);
            for (BlockPos at : BlockPos.betweenClosed(wall.offset(0, 0, -2), wall.offset(0, 6, 2))) c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            c.assertFalse(f.launch(80), "A full wall cannot be crossed by a leap");
            float health = f.target.getHealth();
            c.assertFalse(f.helpers.strike(f.golem, f.target, 80), "No distant or occluded aerial strike is possible");
            c.assertValueEqual(f.target.getHealth(), health, "Wall obstruction causes no damage");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena") public void aerialRolesLeadBoundsCooldownAndGuardLeashAreDeterministic(GameTestHelper c) {
        try (var f = new Arena(c, "tactic-roles")) {
            var beta = f.add("beta", f.start.add(0, 0, 2));
            f.golem.setTarget(f.target); beta.setTarget(f.target);
            c.assertFalse(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, f.target, 80), "The rotating second role leaves alpha on the ground");
            c.assertTrue(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 80), "The selected beta role starts the same-target dive");
            c.assertFalse(f.launch(160), "Only one peer can be in the same-target aerial sequence");
            f.helpers.cancelAerial(beta); beta.setOnGround(true);
            c.assertFalse(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 81), "Cancelling cannot skip the per-helper leap cooldown");
            f.target.setDeltaMovement(100, 0, 0);
            var prediction = f.helpers.intercept(f.golem, f.record(f.golem), f.owner, f.target);
            c.assertTrue(prediction != null && prediction.distanceTo(f.target.position()) <= 2.001, "Very fast target motion has at most a two-block lead");
            f.helpers.mode(f.owner, "alpha", AgentCompanions.Mode.GUARD);
            c.assertFalse(f.helpers.airClear(f.golem, f.record(f.golem), f.owner, f.start, f.start.add(13.9, 0, 0), 3), "A guard arc cannot extend outside its three-dimensional leash");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena") public void nativeComboKeepsAttackCadenceAndCancelsOnProfileRevocation(GameTestHelper c) {
        try (var f = new Arena(c, "tactic-cadence")) {
            f.golem.setPos(f.target.position().add(-1.6, 0, 0));
            float before = f.target.getHealth();
            c.assertTrue(f.helpers.strike(f.golem, f.target, 20), "First normal melee strike succeeds");
            float after = f.target.getHealth();
            c.assertTrue(after < before, "Only vanilla attack damage is applied");
            c.assertFalse(f.helpers.strike(f.golem, f.target, 39), "Follow-up cannot bypass twenty-tick attack cadence");
            c.assertValueEqual(f.target.getHealth(), after, "Early follow-up adds no second damage event");
            f.golem.setPos(f.start); f.golem.setOnGround(true); f.target.setPos(f.start.add(5, 0, 0));
            f.target.setDeltaMovement(Vec3.ZERO);
            c.assertTrue(f.launch(80), "A fresh valid aerial sequence can start");
            f.helpers.profile(f.owner, "alpha", AgentCompanions.Profile.DEBUG);
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "Passive profile change cancels the active aerial state immediately");
            c.assertTrue(f.golem.getDeltaMovement().y <= 0 && !f.golem.isNoGravity(), "Cancelled ascent stops steering and leaves gravity enabled");
            c.assertFalse(f.golem.doHurtTarget(c.getLevel(), f.target), "A passive helper cannot force the follow-up damage");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena") public void playerDiveNeedsAnActiveOrderAndCeasefireOrDeopStopsIt(GameTestHelper c) {
        try (var f = new Arena(c, "tactic-owner")) {
            var target = new ModeGameTests().player(c, "tactic-player");
            boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
            try {
                c.getLevel().getGameRules().set(GameRules.PVP, true, f.helpers.server);
                target.setGameMode(GameType.SURVIVAL); target.setPos(f.start.add(5, 0, 0));
                f.golem.setTarget(target);
                c.assertFalse(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, 80), "Naming a player alone cannot authorize a dive");
                c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "Fixture activates the exact order through the final reviewed-assignment method");
                c.assertTrue(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, 80), "Approved exact target can be pursued");
                f.helpers.ceasefire(f.owner);
                c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "Ceasefire cancels the airborne sequence immediately");
                f.golem.setPos(target.position().add(-1, 0, 0));
                c.assertFalse(f.golem.doHurtTarget(c.getLevel(), target), "Even a forced close hit cannot bypass the cancelled player order");
                f.golem.setPos(f.start); f.golem.setOnGround(true);
                c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "Another sequence requires another exact reviewed assignment");
                c.assertTrue(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, 160), "A later approved sequence respects the cooldown");
                OperatorGameTests.deop(f.owner); f.helpers.tickAerial(f.golem, 161);
                c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "OP4 loss cancels the sequence before its next movement step");
                c.assertFalse(f.golem.doHurtTarget(c.getLevel(), target), "Revocation cannot leave a damaging follow-up behind");

                OperatorGameTests.level(f.owner, LevelBasedPermissionSet.OWNER);
                f.golem.setPos(f.start); f.golem.setOnGround(true);
                f.helpers.mode(f.owner, "alpha", AgentCompanions.Mode.GUARD);
                var beta = f.add("beta", f.start.add(0, 0, 2));
                f.helpers.mode(f.owner, "beta", AgentCompanions.Mode.GUARD);
                for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(f.start).offset(11,-1,-3), BlockPos.containing(f.start).offset(17,-1,3)))
                    c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                target.setPos(f.start.add(13.5, 0, 0));
                c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "A fresh reviewed target remains inside both guard anchors");
                f.golem.setTarget(target); beta.setTarget(target);
                c.assertTrue(f.helpers.landingClear(f.golem, target.position().add(1.6,0,0)), "Rejected flank has clear, supported terrain");
                c.assertTrue(f.helpers.playerPursuitReady(f.golem, f.record(f.golem), f.owner, target), "The player itself is within the guard leash");
                c.assertTrue(f.helpers.flankPoint(f.golem, f.record(f.golem), f.owner, target) == null, "A valid player target cannot steer its flanking guard beyond fourteen blocks");
            } finally {
                c.getLevel().getGameRules().set(GameRules.PVP, pvp, f.helpers.server);
                f.helpers.server.getPlayerList().remove(target);
            }
        }
        c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena") public void ultimateMaceUsesNativeWeaponSmashBonus(GameTestHelper c) {
        try(var f=new Arena(c,"tactic-mace")) {
            f.golem.setPos(f.target.position().add(-1.6,0,0));
            AgentWeapons.mace(f.golem,f.target);
            f.golem.setOnGround(false);f.golem.fallDistance=4;f.golem.setDeltaMovement(0,-.5,0);
            c.assertTrue(net.minecraft.world.item.MaceItem.canSmashAttack(f.golem),"A real falling helper qualifies for native mace bonus");
            float before=f.target.getHealth();
            c.assertTrue(f.golem.doHurtTarget(c.getLevel(),f.target),"Registered Ultimate helper uses MobEntity's actual item attack");
            c.assertTrue(before-f.target.getHealth()>25,"Native mace fall bonus exceeds an ordinary golem punch");
            c.assertTrue(f.golem.getMainHandItem().is(net.minecraft.world.item.Items.MACE),"Attack uses a real mace stack");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=60) public void ultimateSpearNativeUseActuallyPiercesHostile(GameTestHelper c) {
        var f=new Arena(c,"tactic-spear");
        try {
            f.owner.setPos(f.start.add(0,0,-4));
            f.golem.setPos(f.start);
            // A low roof deliberately selects native ground spear charging.
            var roof=net.minecraft.core.BlockPos.containing(f.start).above(3);
            for(var at:net.minecraft.core.BlockPos.betweenClosed(roof.offset(-1,0,-2),roof.offset(7,0,2)))c.getLevel().setBlockAndUpdate(at,Blocks.STONE.defaultBlockState());
            AgentWeapons.spear(f.golem,f.target);
            f.golem.getNavigation().moveTo(f.target,1.1);
            c.assertTrue(f.golem.isUsingItem(),"The native spear use countdown starts");
            boolean[] charged={false};
            c.failIfEver(()->{
                if(charged[0] || f.target.getHealth()>=500)return;
                try {
                    c.assertTrue(f.golem.stabbedEntities(e->e==f.target)>0,"Vanilla KineticWeaponComponent records the real damaging contact");
                    c.assertFalse(f.helpers.lastAttack.containsKey(f.golem.getUUID()),"No ordinary melee strike accounts for the charged spear damage");
                    c.assertTrue(f.golem.getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SPEAR),"Damage is delivered while a real spear is charging");
                    charged[0]=true;c.succeed();
                } finally {f.close();}
            });
            c.runAfterDelay(35,()->{
                if(charged[0])return;
                try {c.assertTrue(charged[0],"Native ground spear charge must cause damage; use="+f.golem.getTicksUsingItem()+" hp="+f.target.getHealth()+" age="+f.golem.tickCount+" range="+f.golem.isWithinMeleeAttackRange(f.target)+" pos="+f.golem.position()+" target="+f.target.position());}
                finally {f.close();}
            });
        } catch(Throwable failure) {f.close();throw failure;}
    }

    @GameTest(structure="convergence_tests:combat_arena") public void ultimateUsesMaceInsideNativeSpearMinimumReach(GameTestHelper c) {
        try(var f=new Arena(c,"tactic-close")) {
            f.owner.setPos(f.start.add(0,0,-4));
            f.golem.setPos(f.target.position().add(-1.6,0,0));
            f.helpers.control(f.golem,f.record(f.golem),80);
            c.assertTrue(f.golem.getMainHandItem().is(net.minecraft.world.item.Items.MACE),"Close combat switches from the spear's dead zone to a real mace: "+f.golem.getMainHandItem().getHoverName().getString());
            c.assertTrue(f.target.getHealth()<500,"Normal control performs a real close mace hit");
            float hp=f.target.getHealth();f.helpers.control(f.golem,f.record(f.golem),85);
            c.assertValueEqual(f.target.getHealth(),hp,"Weapon switching cannot bypass native attack cadence");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena") public void nativeWeaponsCannotDamagePushOrDismountUnapprovedPlayers(GameTestHelper c) {
        try(var f=new Arena(c,"tactic-pierce-guard")) {
            var player=new ModeGameTests().player(c,"tactic-unapproved");
            try {
                player.setGameMode(GameType.SURVIVAL);player.setPos(f.start.add(1.6,0,0));
                var ride=EntityTypes.PIG.create(c.getLevel(),EntitySpawnReason.COMMAND);ride.setPos(player.position());c.getLevel().addFreshEntity(ride);player.startRiding(ride,true,false);
                AgentWeapons.spear(f.golem,f.target);
                var original=f.target.position();
                f.target.setPos(f.start.add(20,0,0));
                c.assertFalse(f.golem.stabAttack(net.minecraft.world.entity.EquipmentSlot.MAINHAND,f.target,100,true,true,true),"A forced native spear call cannot reach a distant hostile");
                f.target.setPos(f.start.add(2.4,0,0));
                AgentWeapons.aim(f.golem,f.target);
                var wall=net.minecraft.core.BlockPos.containing(f.start).east();
                for(var at:net.minecraft.core.BlockPos.betweenClosed(wall.below().offset(0,0,-1),wall.above(3).offset(0,0,1)))c.getLevel().setBlockAndUpdate(at,Blocks.STONE.defaultBlockState());
                c.assertFalse(f.golem.stabAttack(net.minecraft.world.entity.EquipmentSlot.MAINHAND,f.target,100,true,true,true),"A forced native spear call cannot pierce through a solid wall");
                c.assertValueEqual(f.target.getHealth(),500f,"Rejected distant and occluded spear calls preserve hostile health");
                for(var at:net.minecraft.core.BlockPos.betweenClosed(wall.offset(0,0,-1),wall.above(3).offset(0,0,1)))c.getLevel().setBlockAndUpdate(at,Blocks.AIR.defaultBlockState());
                f.target.setPos(original);f.golem.getSensing().tick();
                AgentWeapons.spear(f.golem,player);
                float hp=player.getHealth();var velocity=player.getDeltaMovement();
                c.assertFalse(f.golem.stabAttack(net.minecraft.world.entity.EquipmentSlot.MAINHAND,player,100,true,true,true),"Native pierce rejects all side effects before an unapproved target is touched");
                c.assertTrue(player.getVehicle()==ride,"Rejected spear cannot dismount player");
                c.assertValueEqual(player.getHealth(),hp,"Rejected spear preserves health");c.assertValueEqual(player.getDeltaMovement(),velocity,"Rejected spear preserves velocity");
                AgentWeapons.mace(f.golem,player);f.golem.fallDistance=4;
                c.assertFalse(f.golem.doHurtTarget(c.getLevel(),player),"Native mace cannot bypass both approvals");
                f.golem.setPos(f.target.position().add(-1.6,0,0));
                ride.setPos(f.target.position().add(2,0,0));player.setPos(ride.position());
                var beforeSplash=player.getDeltaMovement();f.golem.fallDistance=4;
                c.assertTrue(f.golem.doHurtTarget(c.getLevel(),f.target),"A valid native mace smash still hits the hostile");
                c.assertValueEqual(player.getDeltaMovement(),beforeSplash,"Native mace splash cannot push an unapproved bystander");
                c.assertTrue(player.getVehicle()==ride,"Native mace splash does not dismount the bystander");
                OperatorGameTests.deop(f.owner);f.helpers.control(f.golem,f.record(f.golem),80);
                c.assertFalse(f.golem.isUsingItem() || f.golem.isFallFlying(),"Revoking owner permission stops both native use and glide");
                player.stopRiding();ride.discard();
            } finally {player.level().getServer().getPlayerList().remove(player);}
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena",maxTicks=140) public void normalServerTicksAcquireHostileAndFinishNativeCombo(GameTestHelper c) {
        var f=new Arena(c,"tactic-autonomous");
        f.owner.setPos(f.start.add(0,0,-4));
        // Do not call control/startAerial/strike: exercise the installed lifecycle tick path.
        c.runAfterDelay(80,()->{
            try {
                c.assertTrue(f.target.getHealth()<500,"Autonomous server ticks cause real hostile damage: hp="+f.target.getHealth()+", target="+f.golem.getTarget()+", weapon="+f.golem.getMainHandItem()+", pos="+f.golem.position());
                c.assertTrue(f.golem.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA),"Autonomous combat equips native flight gear");
                c.assertTrue(f.helpers.lastAttack.containsKey(f.golem.getUUID()),"Autonomous combo reaches a real mace/close strike");
                c.succeed();
            } finally {f.close();}
        });
    }

    @GameTest(structure="convergence_tests:combat_arena") public void helperPlayerAvatarUsesNativeProfileAndTypedMetadataOnly(GameTestHelper c) {
        try(var f=new Arena(c,"tactic-avatar")) {
            var overlay=eu.pb4.polymer.core.api.entity.PolymerEntity.get(f.golem);
            c.assertTrue(overlay instanceof AgentAvatars,"Registered helper receives its own player avatar");
            var avatar=(AgentAvatars)overlay;
            c.assertValueEqual(avatar.getPolymerEntityType(f.owner.connection.getPacketContext()),EntityTypes.PLAYER,"Both ordinary clients receive native PLAYER entity type");
            var packets=new java.util.ArrayList<net.minecraft.network.protocol.Packet<?>>();avatar.onBeforeSpawnPacket(f.owner,packets::add);
            var profile=(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket)packets.getFirst();
            c.assertValueEqual(profile.entries().getFirst().profileId(),f.golem.getUUID(),"Player profile matches spawn UUID");
            c.assertFalse(profile.entries().getFirst().listed(),"Avatar does not inflate tab roster or online count");
            c.assertTrue(profile.entries().getFirst().profile().name().length()<=16,"Native profile has a legal bounded name");
            var texture=profile.entries().getFirst().profile().properties().get("textures").iterator().next();
            c.assertTrue(texture.hasSignature(),"Custom helper skin carries a signed Minecraft texture");
            try(var imageStream=AgentSkin.class.getResourceAsStream("/assets/convergence/helpers/codex-chatgpt.png")) {
                var skin=javax.imageio.ImageIO.read(imageStream);
                c.assertTrue(skin.getWidth()==64&&skin.getHeight()==64,"Playable helper skin has native classic dimensions");
                c.assertTrue((skin.getRGB(12,12)>>>24)==255&&(skin.getRGB(24,24)>>>24)==255,"Base face and chest are opaque");
                c.assertTrue((skin.getRGB(40,8)>>>24)==0,"Unused hat overlay stays transparent");
            } catch(java.io.IOException error){throw new IllegalStateException(error);}
            var other=AgentSkin.profile(java.util.UUID.randomUUID(),"AI_skin_test");
            c.assertValueEqual(other.properties().get("textures").iterator().next(),texture,"Different helper identities share the same robot skin");
            var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),c.getLevel().registryAccess());
            try {
                net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(buf,profile);
                var decoded=net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf);
                c.assertValueEqual(decoded.entries().getFirst().profileId(),f.golem.getUUID(),"Player info round-trips the native wire codec");
                c.assertValueEqual(decoded.entries().getFirst().profile().properties().get("textures").iterator().next(),texture,"Native wire codec preserves the signed skin property");
            } finally {buf.release();}
            var metadata=new java.util.ArrayList<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>>();
            for(var entry:eu.pb4.polymer.core.api.entity.PolymerEntityUtils.getDefaultSynchedEntityData(EntityTypes.IRON_GOLEM))if(entry!=null)metadata.add(entry.value());
            avatar.modifyRawTrackedData(metadata,f.owner,true);
            var human=eu.pb4.polymer.core.api.entity.PolymerEntityUtils.getDefaultSynchedEntityData(EntityTypes.PLAYER);
            for(var entry:metadata)c.assertTrue(entry.id()<human.length&&human[entry.id()]!=null&&entry.serializer()==human[entry.id()].getAccessor().serializer(),"Every avatar metadata value has the native player's type");
            var ordinary=EntityTypes.IRON_GOLEM.create(c.getLevel(),EntitySpawnReason.COMMAND);
            c.assertTrue(eu.pb4.polymer.core.api.entity.PolymerEntity.get(ordinary)==null,"Ordinary golems are unaffected");
            f.helpers.dismiss(f.owner,"alpha");
            c.assertTrue(avatar.viewers.isEmpty(),"Dismissal cleans profile viewers");
        }
        c.succeed();
    }

}
