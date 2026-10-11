package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;

public class InfinityGameTests {
    @GameTest public void crossplayJavaResourcePackContainsModels(TestContext c) {
        var path=java.nio.file.Path.of("crossplay-export/java-resources.zip");
        c.assertTrue(eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.buildMain(path),"Resource pack generation succeeds");
        try(var zip=new java.util.zip.ZipFile(path.toFile())) {
            for(String name:CrossplaySupport.BASES.keySet())
                c.assertTrue(zip.getEntry("assets/convergence/items/"+name+".json")!=null,"Pack contains "+name);
            c.assertTrue(zip.getEntry("assets/minecraft/blockstates/note_block.json")!=null,"Reserved block models generated");
            for(String name:ExpandedGear.BLOCKS)
                c.assertTrue(zip.getEntry("assets/convergence/models/block/"+name+".json")!=null,"Block model "+name);
        } catch(java.io.IOException error) { throw new RuntimeException(error); }
        c.complete();
    }
    @GameTest public void crossplayAllItemsRoundTrip(TestContext c) {
        var p=c.createMockCreativeServerPlayerInWorld();
        var context=xyz.nucleoid.packettweaker.PacketContext.create(p);
        for(var entry:CrossplaySupport.BASES.entrySet()) {
            var original=gear(entry.getKey());
            original.setCount(Math.min(17,original.getMaxCount()));
            original.set(DataComponentTypes.CUSTOM_NAME,net.minecraft.text.Text.literal("My "+entry.getKey()));
            if(entry.getKey().equals("sword"))original.addEnchantment(c.getWorld().getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS),4);
            if(original.isDamageable())original.setDamage(7);
            var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(original,context);
            c.assertEquals(wire.getItem(),entry.getValue(),"Vanilla network item: "+entry.getKey());
            c.assertEquals(wire.get(DataComponentTypes.ITEM_MODEL),CrossplaySupport.id(entry.getKey()),"Model preserved");
            var restored=eu.pb4.polymer.core.api.item.PolymerItemUtils.getRealItemStack(wire,c.getWorld().getRegistryManager());
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(original,restored),"Round-trip preserves components: "+entry.getKey());
            c.assertEquals(restored.getCount(),original.getCount(),"Round-trip count");
        }
        c.complete();
    }
    @GameTest public void crossplayBlocksHaveDistinctVanillaStates(TestContext c) {
        var seen=new java.util.HashSet<String>();
        var p=c.createMockCreativeServerPlayerInWorld();
        var context=xyz.nucleoid.packettweaker.PacketContext.create(p);
        for(String name:ExpandedGear.BLOCKS) {
            var real=Registries.BLOCK.get(CrossplaySupport.id(name)).getDefaultState();
            var wire=eu.pb4.polymer.core.api.block.PolymerBlockUtils.getPolymerBlockState(real,context);
            c.assertEquals(Registries.BLOCK.getId(wire.getBlock()).getNamespace(),"minecraft","Block must be vanilla on wire");
            c.assertTrue(seen.add(CrossplaySupport.stateIdentifier(wire)),"Each block needs its own visual state");
            c.assertEquals(real.getLuminance(),ExpandedGear.light(name),"Real server light remains intact");
        }
        c.complete();
    }
    @GameTest public void crossplayPowerCommandsRestoreSneakingAndEnforceItems(TestContext c) {
        var p=survival(c);
        p.setStackInHand(Hand.MAIN_HAND,gear("sword"));p.setSneaking(false);
        int before=Convergence.state(p).swordMode;
        c.assertEquals(CrossplaySupport.usePower(p,true),1,"Alternate power accepted");
        c.assertEquals(Convergence.state(p).swordMode,(before+1)%3,"Same native sword mode cycle");
        c.assertFalse(p.isSneaking(),"Command must restore crouch state");
        p.setSneaking(true);CrossplaySupport.usePower(p,false);
        c.assertTrue(p.isSneaking(),"Normal power must also restore crouch state");
        p.setStackInHand(Hand.MAIN_HAND,new ItemStack(Items.DIRT));
        c.assertEquals(CrossplaySupport.usePower(p,true),0,"No power without actual Infinity gear");
        p.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
        p.setStackInHand(Hand.MAIN_HAND,gear("sword"));
        c.assertEquals(CrossplaySupport.usePower(p,true),0,"Spectators cannot cast");
        c.complete();
    }
    @GameTest public void crossplaySwapPreservesStacks(TestContext c) {
        var p=c.createMockCreativeServerPlayerInWorld();
        var spear=gear("spear");spear.set(DataComponentTypes.CUSTOM_NAME,net.minecraft.text.Text.literal("Keep me"));
        var mace=gear("mace");
        p.setStackInHand(Hand.MAIN_HAND,spear);p.setStackInHand(Hand.OFF_HAND,mace);
        c.assertEquals(CrossplaySupport.swapHands(p),1,"Swap available without operator rights");
        c.assertTrue(p.getMainHandStack()==mace&&p.getOffHandStack()==spear,"Swap original stacks without copying or loss");
        CrossplaySupport.swapHands(p);
        c.assertTrue(p.getMainHandStack()==spear&&p.getOffHandStack()==mace,"Swapping twice restores inventory");
        c.complete();
    }
    private ItemStack gear(String name) { return new ItemStack(Convergence.ITEMS.get("convergence:"+name)); }
    private LivingEntity target(TestContext c, int totems) {
        var t=c.spawnMob(EntityType.HUSK,3,2,3);
        t.setAiDisabled(true); t.setNoGravity(true);
        if(totems>0)t.setStackInHand(Hand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        if(totems>1)t.setStackInHand(Hand.MAIN_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        return t;
    }
    private ServerPlayerEntity player(TestContext c, LivingEntity t) {
        var p=c.createMockCreativeServerPlayerInWorld();
        p.setPosition(t.getX()-2,t.getY(),t.getZ());p.setNoGravity(true);p.setOnGround(false);
        p.setStackInHand(Hand.MAIN_HAND,gear("mace"));p.setStackInHand(Hand.OFF_HAND,gear("spear"));
        p.setVelocity(0,-1,0);p.lookAt(EntityAnchor.EYES,t.getEyePos());
        Convergence.state(p).drop=4;
        return p;
    }
    private void armored(TestContext c, LivingEntity t) {
        var protection=c.getWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        Item[] armor={Items.NETHERITE_HELMET,Items.NETHERITE_CHESTPLATE,Items.NETHERITE_LEGGINGS,Items.NETHERITE_BOOTS};
        EquipmentSlot[] slots={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        for(int i=0;i<4;i++){var stack=new ItemStack(armor[i]);stack.addEnchantment(protection,4);t.equipStack(slots[i],stack);}
        if(t instanceof ServerPlayerEntity sp)sp.playerTick();else t.tick();t.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,200,3));
        c.assertTrue(t.getArmor()>=20,"Real netherite armor must be active");
    }
    @GameTest public void equipmentAndNativeGlider(TestContext c) {
        c.assertEquals(Convergence.ITEMS.size(),40,"Original 23 items, six blocks, eight cosmetic armor pieces, two wands, and backpack");
        c.assertTrue(gear("chestplate").contains(DataComponentTypes.GLIDER),"Winged armor must enable native flight");
        for(String name:new String[]{"helmet","chestplate","leggings","boots","sword","mace","spear"})
            c.assertTrue(gear(name).contains(DataComponentTypes.UNBREAKABLE),"Gear must remain unbreakable: "+name);
        c.assertTrue(gear("mace").isIn(TagKey.of(RegistryKeys.ITEM,Identifier.ofVanilla("enchantable/mace"))),"Mace enchantment tags must load");
        c.complete();
    }
    @GameTest public void fullSetKeepsPowersWithoutSlowFalling(TestContext c) {
        var p=player(c,target(c,0));
        p.equipStack(EquipmentSlot.HEAD,gear("helmet"));p.equipStack(EquipmentSlot.CHEST,gear("chestplate"));
        p.equipStack(EquipmentSlot.LEGS,gear("leggings"));p.equipStack(EquipmentSlot.FEET,gear("boots"));
        Convergence.armor(p);
        c.assertTrue(p.hasStatusEffect(StatusEffects.HEALTH_BOOST),"Full set has extra health");
        c.assertEquals(p.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier(),3,"Resistance IV");
        c.assertFalse(p.hasStatusEffect(StatusEffects.SLOW_FALLING),"Never grant Slow Falling");c.complete();
    }
    @GameTest(maxTicks=30) public void actualUseSpearTotemMaceAgainstProtectionArmor(TestContext c) {
        var t=target(c,1);armored(c,t);var p=player(c,t);
        p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND);
        c.assertEquals(Convergence.state(p).armed-Convergence.clock,200L,"Use must arm for ten seconds");
        Convergence.updateCombo(p);
        c.assertTrue(t.isAlive(),"Native totem must save the first hit");
        c.assertTrue(t.getOffHandStack().isEmpty(),"Minecraft itself must consume the offhand totem");
        c.assertFalse(Convergence.finish(p,t),"Cannot deliver second hit in the spear tick");
        p.setVelocity(Vec3d.ZERO);
        c.waitAndRun(3,()->{c.assertFalse(t.isAlive(),"Mace must finish one-totem protected target on a later tick");c.complete();});
    }
    @GameTest(maxTicks=30) public void secondNativeTotemStillSavesTarget(TestContext c) {
        var t=target(c,2);var p=player(c,t);c.assertTrue(Convergence.thrust(p,t),"Charged first hit");p.setVelocity(Vec3d.ZERO);
        c.waitAndRun(3,()->{
            c.assertTrue(t.isAlive(),"Second native totem must remain effective");
            c.assertTrue(t.getMainHandStack().isEmpty()&&t.getOffHandStack().isEmpty(),"Both totems consumed by Minecraft");
            c.assertTrue(Convergence.state(p).target==null,"Successful sequence cannot schedule a third hit");c.complete();
        });
    }
    @GameTest(maxTicks=30) public void manualFinisherWaitsOneTick(TestContext c) {
        var t=target(c,1);var p=player(c,t);p.setStackInHand(Hand.MAIN_HAND,gear("spear"));p.setStackInHand(Hand.OFF_HAND,ItemStack.EMPTY);
        c.assertTrue(Convergence.thrust(p,t),"Manual spear must land");p.setStackInHand(Hand.MAIN_HAND,gear("mace"));p.setVelocity(Vec3d.ZERO);
        c.assertFalse(Convergence.finish(p,t),"Manual path cannot bypass timing guard");
        c.waitAndRun(2,()->{c.assertTrue(Convergence.finish(p,t),"Manual finisher allowed on later tick");c.assertFalse(t.isAlive(),"Finisher lethal after native totem");c.complete();});
    }
    @GameTest(maxTicks=30) public void leavingMaceRangePreventsRemoteFinisher(TestContext c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);p.setPosition(t.getX()-5.5,t.getY(),t.getZ());p.setVelocity(Vec3d.ZERO);
        c.waitAndRun(3,()->{c.assertTrue(t.isAlive(),"No remote mace hit outside 4.5 blocks");c.assertTrue(Convergence.state(p).target!=null,"Can close the gap during the live window");c.complete();});
    }
    @GameTest(maxTicks=30) public void changingOffhandCancelsAutomaticCombo(TestContext c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);p.setStackInHand(Hand.OFF_HAND,ItemStack.EMPTY);p.setVelocity(Vec3d.ZERO);
        c.waitAndRun(2,()->{c.assertTrue(t.isAlive(),"Weapon change prevents finisher");c.assertTrue(Convergence.state(p).target==null,"Stale mark removed");c.complete();});
    }
    @GameTest public void pendingSpearMarkCannotBeOverwritten(TestContext c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);
        c.assertFalse(Convergence.arm(p),"Rearm cannot overwrite a mark");
        c.assertFalse(Convergence.thrust(p,t),"Repeated spear cannot overwrite a mark");c.complete();
    }
    @GameTest public void groundedMeleeUsesNativeAttributes(TestContext c) {
        for(String name:new String[]{"mace","spear","sword"}){
            var t=target(c,1);var p=player(c,t);p.setStackInHand(Hand.MAIN_HAND,gear(name));p.setOnGround(true);p.setVelocity(Vec3d.ZERO);Convergence.state(p).drop=0;
            p.playerTick();
            var result=AttackEntityCallback.EVENT.invoker().interact(p,c.getWorld(),Hand.MAIN_HAND,t,null);
            c.assertTrue(result==ActionResult.PASS,"Grounded "+name+" must allow vanilla attack");
            c.assertTrue(p.getAttributeValue(EntityAttributes.ATTACK_DAMAGE)>30,"Weapon attributes must actually be equipped: "+name+" = "+p.getAttributeValue(EntityAttributes.ATTACK_DAMAGE));
            float before=t.getHealth();p.attack(t);
            c.assertTrue(t.getHealth()<before||t.getOffHandStack().isEmpty(),"Native "+name+" must damage the target");
        }
        c.complete();
    }
    @GameTest public void poweredMaceHasCooldownAndOneDamagePath(TestContext c) {
        var t=target(c,2);var p=player(c,t);p.setStackInHand(Hand.OFF_HAND,ItemStack.EMPTY);
        c.assertTrue(Convergence.melee(p,t)==ActionResult.SUCCESS,"Powered smash consumes vanilla attack");
        c.assertTrue(Convergence.state(p).cooldown.get("melee_smash")>Convergence.clock,"Smash gets a cooldown");
        c.assertTrue(Convergence.melee(p,t)==ActionResult.PASS,"Cooldown prevents repeated scripted damage; vanilla remains available");
        c.assertTrue(t.isAlive(),"Only one scripted hit was applied");c.complete();
    }
    @GameTest public void onlyActualInfinityBootsCancelFalls(TestContext c) {
        var p=player(c,target(c,0));var source=c.getWorld().getDamageSources().fall();
        p.equipStack(EquipmentSlot.FEET,gear("helmet"));
        c.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p,source,20),"Other custom gear is not fall protection");
        p.equipStack(EquipmentSlot.FEET,gear("boots"));
        c.assertFalse(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p,source,20),"Actual boots cancel fall damage");c.complete();
    }
    @GameTest public void blinkClearsFallAndComboState(TestContext c) {
        var t=target(c,1);var p=player(c,t);p.setStackInHand(Hand.MAIN_HAND,gear("sword"));
        p.setYaw(0);p.setPitch(0);p.setSneaking(false);p.fallDistance=25;
        var s=Convergence.state(p);s.swordMode=1;s.armed=Convergence.clock+10;s.target=t.getUuid();s.drop=25;
        Vec3d before=p.getEntityPos();Convergence.swordPower(p);
        c.assertTrue(p.getEntityPos().distanceTo(before)>=2,"Blink actually moved the player");
        c.assertEquals(p.fallDistance,0.0,"Blink resets engine fall distance");
        c.assertEquals(p.getVelocity(),Vec3d.ZERO,"Blink resets velocity");
        c.assertTrue(s.target==null&&s.armed==0&&s.drop==0,"Blink clears stale combo charge");c.complete();
    }
    @GameTest public void pendingComboAllowsOtherTargetsNativeMelee(TestContext c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);
        var other=c.spawnMob(EntityType.HUSK,3,2,5);other.setAiDisabled(true);
        c.assertTrue(Convergence.melee(p,other)==ActionResult.PASS,"Unrelated target must retain vanilla melee while mark is pending");
        c.assertTrue(Convergence.melee(p,t)==ActionResult.SUCCESS,"Same-tick marked target must not get an extra native hit");
        c.assertTrue(t.isAlive(),"Mandatory delay protects the original target until next tick");c.complete();
    }
    @GameTest public void rejectedSpearKeepsBoundedArmWithoutSpendingCooldown(TestContext c) {
        var t=target(c,1);var p=player(c,t);p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);t.setInvulnerable(true);Convergence.arm(p);
        c.assertFalse(Convergence.thrust(p,t),"Invulnerable target rejects damage");
        c.assertTrue(Convergence.state(p).armed>Convergence.clock,"Transient rejection retains the bounded arm for retry");
        c.assertTrue(Convergence.state(p).target==null,"Rejected hit cannot open a follow-up");
        c.assertFalse(Convergence.state(p).cooldown.containsKey("spear"),"Failed hit costs no cooldown");c.complete();
    }
    @GameTest public void protectedTargetsAreExcluded(TestContext c) {
        var t=target(c,0);var p=player(c,t);t.addCommandTag("convergence_friend");
        c.assertFalse(Convergence.hurt(p,t,3000),"Friendly target cannot receive scripted hits");
        var creative=c.createMockCreativeServerPlayerInWorld();
        c.assertFalse(Convergence.valid(p,creative),"Creative target excluded");c.complete();
    }
    private ServerPlayerEntity survival(TestContext c) {
        var profile=new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"combo-survival");
        var data=net.minecraft.server.network.ConnectedClientData.createDefault(profile,false);
        var p=new ServerPlayerEntity(c.getWorld().getServer(),c.getWorld(),profile,data.syncedOptions());
        var connection=new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getWorld().getServer().getPlayerManager().onPlayerConnect(connection,p,data);
        p.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);p.setNoGravity(true);
        p.setInvulnerable(false);p.getAbilities().invulnerable=false;
        c.assertFalse(p.isCreative(),"Real Survival player required, not creative-only mock");
        return p;
    }
    @GameTest(maxTicks=40) public void survivalPlayerTotemCombo(TestContext c) {
        var t=survival(c);var pos=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(3,3,3));
        t.setPosition(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);armored(c,t);
        t.setStackInHand(Hand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        var p=survival(c);p.setPosition(t.getX()-2,t.getY(),t.getZ());p.setOnGround(false);
        p.setStackInHand(Hand.MAIN_HAND,gear("mace"));p.setStackInHand(Hand.OFF_HAND,gear("spear"));
        p.setVelocity(0,-1,0);p.lookAt(EntityAnchor.EYES,t.getEyePos());
        p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND);
        Convergence.updateCombo(p);p.setVelocity(Vec3d.ZERO);
        c.assertTrue(t.isAlive(),"Native totem saves actual Survival target from spear");
        c.assertTrue(t.getOffHandStack().isEmpty(),"Native Survival totem consumed");
        c.waitAndRun(3,()->{c.assertFalse(t.isAlive(),"Automatic mace finishes real armored Survival player");c.complete();});
    }
    @GameTest(maxTicks=40) public void delayedMeleeAfterComboHasNoThirdScriptedHit(TestContext c) {
        var t=target(c,2);var p=player(c,t);Convergence.thrust(p,t);p.setVelocity(Vec3d.ZERO);
        c.waitAndRun(3,()->{
            c.assertTrue(t.isAlive(),"Second totem saves target");p.setOnGround(false);p.setVelocity(0,-1,0);
            c.assertTrue(Convergence.melee(p,t)==ActionResult.PASS,"Delayed swing must not append scripted smash");
            c.assertTrue(t.isAlive(),"No third scripted hit");c.complete();
        });
    }
    @GameTest public void ingotDoesNotConsumeUse(TestContext c) {
        var p=player(c,target(c,0));p.setStackInHand(Hand.MAIN_HAND,gear("ingot"));
        c.assertTrue(p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND)==ActionResult.PASS,"Ingot must allow ordinary item Use");c.complete();
    }

    @GameTest public void survivalChestplateStartsNativeGlide(TestContext c) {
        var p=survival(c);var pos=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(1,4,1));
        p.setPosition(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);p.setOnGround(false);
        p.equipStack(EquipmentSlot.CHEST,gear("chestplate"));p.setVelocity(0,-0.4,0);
        c.assertTrue(p.checkGliding(),"Native player must be able to start gliding with Infinity chestplate");
        c.assertTrue(p.isGliding(),"Native gliding flag must be active");c.complete();
    }
    @GameTest public void stormDoesNotDoubleHitSplashTargets(TestContext c) {
        var primary=target(c,2);var secondary=c.spawnMob(EntityType.HUSK,3,2,5);secondary.setAiDisabled(true);
        secondary.setStackInHand(Hand.MAIN_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        secondary.setStackInHand(Hand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        var p=player(c,primary);p.setStackInHand(Hand.MAIN_HAND,gear("sword"));Convergence.swordPower(p);
        c.assertTrue(secondary.isAlive(),"Splash target should survive native totem");
        int remaining=(secondary.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)?1:0)+(secondary.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)?1:0);
        c.assertEquals(remaining,1,"Splash/shockwave overlap must produce exactly one hit");c.complete();
    }

    @GameTest(maxTicks=20) public void spearAndMaceUseDistinctServerTicks(TestContext c) {
        var t=target(c,2);var p=player(c,t);Convergence.arm(p);Convergence.updateCombo(p);
        long spearTick=c.getWorld().getServer().getTicks();long abilityTick=Convergence.clock;
        p.setVelocity(Vec3d.ZERO);
        // Running end-tick work in the same tick must not advance the ability clock.
        Convergence.tick(c.getWorld().getServer());
        c.assertEquals(Convergence.clock,abilityTick,"End-tick work must not advance the clock");
        c.assertTrue(Convergence.state(p).target!=null,"No finisher during the first server tick");
        c.waitAndRun(2,()->{
            c.assertTrue(c.getWorld().getServer().getTicks()>spearTick,"Server time actually advanced");
            c.assertTrue(Convergence.state(p).target==null,"Automatic finisher completes on later server tick");c.complete();
        });
    }

    private ServerPlayerEntity toolPlayer(TestContext c,String name,net.minecraft.util.math.BlockPos at) {
        var p=survival(c);p.setPosition(at.getX()+0.5,at.getY()+3,at.getZ()+0.5);
        p.setVelocity(Vec3d.ZERO);p.setStackInHand(Hand.MAIN_HAND,gear(name));
        p.lookAt(EntityAnchor.EYES,Vec3d.ofCenter(at));return p;
    }
    @GameTest public void poweredToolsHaveNativeMiningAndEnchantments(TestContext c) {
        for(String name:new String[]{"pickaxe","axe","shovel","hoe"}) {
            var stack=gear(name);
            c.assertTrue(stack.contains(DataComponentTypes.UNBREAKABLE),"Unbreakable "+name);
            c.assertTrue(stack.contains(DataComponentTypes.TOOL),"Native tool rules "+name);
            c.assertTrue(stack.hasGlint(),"Visible enchanted tool "+name);
            c.assertTrue(stack.isIn(TagKey.of(RegistryKeys.ITEM,Identifier.ofVanilla("enchantable/mining"))),"Mining enchantments "+name);
        }
        c.assertTrue(gear("pickaxe").isSuitableFor(net.minecraft.block.Blocks.DIAMOND_ORE.getDefaultState()),"Pickaxe can harvest diamond ore");
        c.assertTrue(gear("pickaxe").getMiningSpeedMultiplier(net.minecraft.block.Blocks.STONE.getDefaultState())>=40,"Powered native mining speed");c.complete();
    }
    @GameTest public void pickaxeExcavatesOnlyItsNineBlockPlane(TestContext c) {
        var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,1,2));var w=c.getWorld();
        for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP))w.setBlockState(q,net.minecraft.block.Blocks.STONE.getDefaultState());
        w.setBlockState(at.add(1,0,1),net.minecraft.block.Blocks.BEDROCK.getDefaultState());
        w.setBlockState(at.down(),net.minecraft.block.Blocks.DIAMOND_ORE.getDefaultState());
        var p=toolPlayer(c,"pickaxe",at);
        c.assertEquals(PoweredTools.use(p,"convergence:pickaxe",at,net.minecraft.util.math.Direction.UP),8,"Eight stone blocks excavated around bedrock");
        c.assertTrue(w.getBlockState(at.add(1,0,1)).isOf(net.minecraft.block.Blocks.BEDROCK),"Bedrock untouched");
        c.assertTrue(w.getBlockState(at.down()).isOf(net.minecraft.block.Blocks.DIAMOND_ORE),"Next layer untouched");
        w.setBlockState(at,net.minecraft.block.Blocks.STONE.getDefaultState());
        c.assertEquals(PoweredTools.use(p,"convergence:pickaxe",at,net.minecraft.util.math.Direction.UP),0,"Cooldown prevents repeat excavation");c.complete();
    }
    @GameTest public void shovelSkipsStoneAndAdventureCannotExcavate(TestContext c) {
        var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,1,2));var w=c.getWorld();
        for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP))w.setBlockState(q,net.minecraft.block.Blocks.DIRT.getDefaultState());
        w.setBlockState(at.add(1,0,1),net.minecraft.block.Blocks.STONE.getDefaultState());
        var p=toolPlayer(c,"shovel",at);p.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);
        c.assertEquals(PoweredTools.use(p,"convergence:shovel",at,net.minecraft.util.math.Direction.UP),0,"Adventure cannot use terrain powers");
        p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        var hit=new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(at),net.minecraft.util.math.Direction.UP,at,false);
        var result=p.getMainHandStack().getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(p,Hand.MAIN_HAND,hit));
        c.assertTrue(result==ActionResult.SUCCESS,"Actual block Use dispatches shovel power");
        int removed=0;for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP))if(w.getBlockState(q).isAir())removed++;
        c.assertEquals(removed,8,"Only soil excavated");
        c.assertTrue(w.getBlockState(at.add(1,0,1)).isOf(net.minecraft.block.Blocks.STONE),"Stone untouched");c.complete();
    }
    @GameTest public void timberIsBoundedAndLeavesOtherSpecies(TestContext c) {
        var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,1,2));var w=c.getWorld();
        for(int x=0;x<5;x++)for(int z=0;z<7;z++)w.setBlockState(at.add(x,0,z),net.minecraft.block.Blocks.OAK_LOG.getDefaultState());
        w.setBlockState(at.add(-1,0,0),net.minecraft.block.Blocks.BIRCH_LOG.getDefaultState());
        var p=toolPlayer(c,"axe",at);
        c.assertEquals(PoweredTools.use(p,"convergence:axe",at,net.minecraft.util.math.Direction.UP),32,"Timber capped at 32 logs");
        int remaining=0;for(int x=0;x<5;x++)for(int z=0;z<7;z++)if(w.getBlockState(at.add(x,0,z)).isOf(net.minecraft.block.Blocks.OAK_LOG))remaining++;
        c.assertEquals(remaining,3,"Only 32 of 35 logs felled");
        c.assertTrue(w.getBlockState(at.add(-1,0,0)).isOf(net.minecraft.block.Blocks.BIRCH_LOG),"Other species remains");c.complete();
    }
    @GameTest public void farmBloomConsumesSeedsAndGrowsWheat(TestContext c) {
        var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,1,2));var w=c.getWorld();
        for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP))w.setBlockState(q,net.minecraft.block.Blocks.DIRT.getDefaultState());
        var p=toolPlayer(c,"hoe",at);p.getInventory().setStack(1,new ItemStack(Items.WHEAT_SEEDS,3));
        c.assertEquals(PoweredTools.use(p,"convergence:hoe",at,net.minecraft.util.math.Direction.UP),12,"Nine tilled and three planted");
        c.assertTrue(p.getInventory().getStack(1).isEmpty(),"Survival planting consumes exactly three seeds");
        int crops=0;for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP)) {
            c.assertTrue(w.getBlockState(q).isOf(net.minecraft.block.Blocks.FARMLAND),"All soil tilled");
            if(w.getBlockState(q.up()).isOf(net.minecraft.block.Blocks.WHEAT))crops++;
        }
        c.assertEquals(crops,3,"No crops created without seeds");
        PoweredTools.farm(p,at);
        c.assertEquals(w.getBlockState(at.up()).get(net.minecraft.block.CropBlock.AGE),7,"Existing wheat matures");c.complete();
    }
    @GameTest public void secondaryToolsAffectHostilesAndRenewalHeals(TestContext c) {
        var hostile=target(c,2);var p=player(c,hostile);p.setStackInHand(Hand.MAIN_HAND,gear("pickaxe"));p.setVelocity(Vec3d.ZERO);
        var friend=c.spawnMob(EntityType.HUSK,3,2,4);friend.setAiDisabled(true);friend.addCommandTag("convergence_friend");
        float friendHealth=friend.getHealth();p.setSneaking(true);p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND);
        c.assertTrue(hostile.getVelocity().x<0,"Gravity Well pulls hostile toward player");
        c.assertEquals(friend.getHealth(),friendHealth,"Friendly tagged entity is unharmed");
        c.assertTrue(friend.getVelocity().lengthSquared()==0,"Friendly tagged entity is not moved");
        var healer=survival(c);healer.setStackInHand(Hand.MAIN_HAND,gear("hoe"));healer.setHealth(5);healer.setOnFireFor(10);
        healer.setSneaking(true);healer.getMainHandStack().getItem().use(c.getWorld(),healer,Hand.MAIN_HAND);
        c.assertEquals(healer.getHealth(),healer.getMaxHealth(),"Renewal restores health");
        c.assertFalse(healer.isOnFire(),"Renewal extinguishes fire");
        c.assertTrue(healer.hasStatusEffect(StatusEffects.REGENERATION),"Renewal grants regeneration");
        healer.setHealth(5);PoweredTools.combat(healer,"convergence:hoe");c.assertEquals(healer.getHealth(),5.0f,"Renewal cooldown enforced");c.complete();
    }

    @GameTest public void infinityBlocksRegisterWithLightAndNativeMining(TestContext c) {
        for(String path:ExpandedGear.BLOCKS) {
            var block=Registries.BLOCK.get(Identifier.of("convergence",path));
            c.assertTrue(!block.getDefaultState().isAir(),"Registered block: "+path);
            c.assertTrue(Convergence.ITEMS.get("convergence:"+path)!=null,"Placeable block item: "+path);
            c.assertTrue(gear("pickaxe").isSuitableFor(block.getDefaultState()),"Native pickaxe harvests "+path);
        }
        c.assertEquals(Registries.BLOCK.get(Identifier.of("convergence","radiant_infinity")).getDefaultState().getLuminance(),15,"Radiant block emits full light");
        c.complete();
    }
    @GameTest public void pickaxeExcavatesOnlyTheFourCustomBlocks(TestContext c) {
        var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,1,2));var w=c.getWorld();
        for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP))w.setBlockState(q,Registries.BLOCK.get(Identifier.of("convergence","infinity_block")).getDefaultState());
        var p=toolPlayer(c,"pickaxe",at);
        c.assertEquals(PoweredTools.use(p,"convergence:pickaxe",at,net.minecraft.util.math.Direction.UP),9,"All nine Infinity blocks excavated");
        for(var q:PoweredTools.plane(at,net.minecraft.util.math.Direction.UP))c.assertTrue(w.getBlockState(q).isAir(),"Custom blocks removed natively");
        c.complete();
    }
    @GameTest public void customTotemSavesLethalDamageButNotKillCommands(TestContext c) {
        var p=survival(c);p.setStackInHand(Hand.OFF_HAND,gear("totem"));
        c.assertTrue(p.getOffHandStack().contains(DataComponentTypes.DEATH_PROTECTION),"Custom totem uses native death protection");
        p.damage(c.getWorld(),c.getWorld().getDamageSources().generic(),1000.0F);
        c.assertTrue(p.isAlive(),"Custom totem saves actual lethal damage");
        c.assertTrue(p.getOffHandStack().isEmpty(),"Exactly one custom totem consumed");
        c.assertTrue(p.hasStatusEffect(StatusEffects.REGENERATION),"Rescue grants regeneration");
        c.assertTrue(p.hasStatusEffect(StatusEffects.FIRE_RESISTANCE),"Rescue grants fire resistance");
        var bypass=survival(c);bypass.setStackInHand(Hand.OFF_HAND,gear("totem"));
        var kill=c.getWorld().getDamageSources().genericKill();
        c.assertTrue(kill.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_INVULNERABILITY),"Kill source has native death-protection bypass tag");
        bypass.damage(c.getWorld(),kill,1000.0F);
        c.assertFalse(bypass.isAlive(),"Kill bypasses custom totem rescue");
        c.complete();
    }
    @GameTest public void nativeTotemHandOrderIncludesCustomTotem(TestContext c) {
        var p=survival(c);p.setStackInHand(Hand.MAIN_HAND,gear("totem"));
        p.setStackInHand(Hand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        p.damage(c.getWorld(),c.getWorld().getDamageSources().generic(),1000.0F);
        c.assertTrue(p.isAlive(),"A held totem handles fatal damage");
        c.assertTrue(p.getMainHandStack().isEmpty(),"Native hand order consumes custom mainhand first");
        c.assertTrue(p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING),"Vanilla offhand totem retained");
        var vanilla=survival(c);vanilla.setStackInHand(Hand.MAIN_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        vanilla.setStackInHand(Hand.OFF_HAND,gear("totem"));
        vanilla.damage(c.getWorld(),c.getWorld().getDamageSources().generic(),1000.0F);
        c.assertTrue(vanilla.getMainHandStack().isEmpty(),"Vanilla mainhand consumed first");
        c.assertTrue(vanilla.getOffHandStack().isOf(Convergence.ITEMS.get("convergence:totem")),"Custom offhand retained");
        c.complete();
    }
    @GameTest(maxTicks=40) public void customTotemSavesSpearButMaceFinishesCombo(TestContext c) {
        var t=survival(c);var pos=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(3,3,3));
        t.setPosition(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);armored(c,t);
        t.setStackInHand(Hand.OFF_HAND,gear("totem"));
        var p=survival(c);p.setPosition(t.getX()-2,t.getY(),t.getZ());p.setOnGround(false);
        p.setStackInHand(Hand.MAIN_HAND,gear("mace"));p.setStackInHand(Hand.OFF_HAND,gear("spear"));
        p.setVelocity(0,-1,0);p.lookAt(EntityAnchor.EYES,t.getEyePos());
        p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND);
        Convergence.updateCombo(p);p.setVelocity(Vec3d.ZERO);
        c.assertTrue(t.isAlive(),"Custom totem saves actual Survival target from spear");
        c.assertTrue(t.getOffHandStack().isEmpty(),"Custom totem consumed by first hit");
        c.waitAndRun(3,()->{c.assertFalse(t.isAlive(),"Automatic mace finishes after custom totem rescue");c.complete();});
    }
    @GameTest public void shieldUsesNativeBlockAndCrouchWard(TestContext c) {
        var p=survival(c);p.setStackInHand(Hand.MAIN_HAND,gear("shield"));
        c.assertTrue(p.getMainHandStack().contains(DataComponentTypes.BLOCKS_ATTACKS),"Native shield defense component retained");
        p.setSneaking(false);
        p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND);
        c.assertTrue(p.isUsingItem(),"Ordinary Use starts native blocking");
        p.stopUsingItem();p.setSneaking(true);
        p.getMainHandStack().getItem().use(c.getWorld(),p,Hand.MAIN_HAND);
        c.assertTrue(p.hasStatusEffect(StatusEffects.RESISTANCE),"Crouch Use grants Ward resistance");
        c.assertTrue(p.hasStatusEffect(StatusEffects.ABSORPTION),"Crouch Use grants Ward absorption");
        c.complete();
    }
    @GameTest public void nativeBowConsumesCustomArrowAndKeepsProjectileType(TestContext c) {
        var p=survival(c);var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,2,2));p.setPosition(Vec3d.ofCenter(at));
        p.setStackInHand(Hand.MAIN_HAND,gear("bow"));
        var ammo=new ItemStack(Convergence.ITEMS.get("convergence:infinity_arrow"),3);p.getInventory().setStack(1,ammo);
        var bow=(net.minecraft.item.BowItem)p.getMainHandStack().getItem();
        c.assertTrue(bow.getProjectiles().test(ammo),"Custom arrows are native bow ammunition");
        int left=bow.getMaxUseTime(p.getMainHandStack(),p)-40;
        c.assertTrue(bow.onStoppedUsing(p.getMainHandStack(),c.getWorld(),p,left),"Fully drawn bow fires");
        c.assertEquals(p.getInventory().getStack(1).getCount(),2,"Native bow consumes one custom arrow");
        var arrows=c.getWorld().getEntitiesByClass(net.minecraft.entity.projectile.PersistentProjectileEntity.class,p.getBoundingBox().expand(5),e->true);
        c.assertTrue(arrows.stream().anyMatch(e->e.getItemStack().isOf(Convergence.ITEMS.get("convergence:infinity_arrow"))),"Fired projectile retains custom arrow type");
        c.assertTrue(((net.minecraft.item.CrossbowItem)gear("crossbow").getItem()).getProjectiles().test(gear("void_arrow")),"Crossbow accepts Void ammo");
        c.complete();
    }
    @GameTest public void nativeCrossbowLoadsAndFiresCustomArrow(TestContext c) {
        var p=survival(c);var at=c.getAbsolutePos(new net.minecraft.util.math.BlockPos(2,2,2));p.setPosition(Vec3d.ofCenter(at));
        var weapon=gear("crossbow");p.setStackInHand(Hand.MAIN_HAND,weapon);
        p.getInventory().setStack(1,new ItemStack(Convergence.ITEMS.get("convergence:void_arrow"),3));
        var crossbow=(net.minecraft.item.CrossbowItem)weapon.getItem();
        c.assertFalse(net.minecraft.item.CrossbowItem.isCharged(weapon),"Custom crossbow starts unloaded");
        int remaining=weapon.getMaxUseTime(p)-net.minecraft.item.CrossbowItem.getPullTime(weapon,p)-1;
        crossbow.usageTick(c.getWorld(),p,weapon,remaining);
        c.assertTrue(net.minecraft.item.CrossbowItem.isCharged(weapon),"Native crossbow loads custom Void ammo");
        c.assertEquals(p.getInventory().getStack(1).getCount(),2,"Loading consumes exactly one Void arrow");
        crossbow.use(c.getWorld(),p,Hand.MAIN_HAND);
        c.assertFalse(net.minecraft.item.CrossbowItem.isCharged(weapon),"Firing clears native charge");
        var arrows=c.getWorld().getEntitiesByClass(net.minecraft.entity.projectile.PersistentProjectileEntity.class,p.getBoundingBox().expand(5),e->true);
        c.assertTrue(arrows.stream().anyMatch(e->e.getItemStack().isOf(Convergence.ITEMS.get("convergence:void_arrow"))),"Fired projectile retains Void type");
        c.complete();
    }
    @GameTest public void arrowEffectsRespectFriendsAndTriggerOnKillingHit(TestContext c) {
        var target=target(c,0);var p=survival(c);p.setPosition(target.getX()-2,target.getY(),target.getZ());
        var nearby=c.spawnMob(EntityType.HUSK,3,2,5);nearby.setAiDisabled(true);nearby.setNoGravity(true);
        var friend=c.spawnMob(EntityType.HUSK,4,2,3);friend.setAiDisabled(true);friend.setNoGravity(true);friend.addCommandTag("convergence_friend");
        var damageType=c.getWorld().getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(Convergence.COMBO);
        var infinity=new net.minecraft.entity.projectile.ArrowEntity(c.getWorld(),p,gear("infinity_arrow"),gear("bow"));
        var radiant=new net.minecraft.entity.damage.DamageSource(damageType,infinity,p);
        float initial=target.getHealth();
        ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(target,radiant,5,5,false);
        c.assertTrue(target.getHealth()<=initial-12,"Infinity arrow adds radiant damage");
        c.assertTrue(target.hasStatusEffect(StatusEffects.GLOWING),"Infinity arrow reveals the target");
        var voidArrow=new net.minecraft.entity.projectile.ArrowEntity(c.getWorld(),p,gear("void_arrow"),gear("bow"));
        var voidSource=new net.minecraft.entity.damage.DamageSource(damageType,voidArrow,p);
        target.setHealth(20);target.setVelocity(Vec3d.ZERO);
        ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(target,voidSource,5,5,false);
        c.assertTrue(target.hasStatusEffect(StatusEffects.SLOWNESS),"Void arrow slows");
        c.assertTrue(target.getVelocity().x<0,"Void arrow pulls toward shooter");
        var fireArrow=new net.minecraft.entity.projectile.ArrowEntity(c.getWorld(),p,gear("starfire_arrow"),gear("bow"));
        var fireSource=new net.minecraft.entity.damage.DamageSource(damageType,fireArrow,p);
        float before=nearby.getHealth(),friendBefore=friend.getHealth();target.setHealth(0);
        c.assertTrue(ServerLivingEntityEvents.ALLOW_DEATH.invoker().allowDeath(target,fireSource,20),"Killing hit still permits death");
        c.assertTrue(nearby.getHealth()<before,"Killing Starfire hit bursts onto nearby hostile");
        c.assertEquals(friend.getHealth(),friendBefore,"Friendly target excluded from Starfire burst");
        c.complete();
    }
}
