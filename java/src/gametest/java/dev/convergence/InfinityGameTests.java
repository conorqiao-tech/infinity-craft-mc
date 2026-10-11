package dev.convergence;

import net.minecraft.world.entity.EntityTypes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;

public class InfinityGameTests {
    private static boolean buildJavaPack(java.nio.file.Path path) {
        try {return !eu.pb4.polymer.resourcepack.api.ResourcePackCreator.forDefault().build(path).hadIssues();}
        catch(java.util.concurrent.ExecutionException|InterruptedException e) {return false;}
    }
    @GameTest public void crossplayJavaResourcePackContainsModels(GameTestHelper c) {
        var path=java.nio.file.Path.of("crossplay-export/java-resources.zip");
        c.assertTrue(buildJavaPack(path),"Resource pack generation succeeds");
        try(var zip=new java.util.zip.ZipFile(path.toFile())) {
            for(String name:CrossplaySupport.BASES.keySet())
                c.assertTrue(zip.getEntry("assets/convergence/items/"+name+".json")!=null,"Pack contains "+name);
            c.assertTrue(zip.getEntry("assets/minecraft/blockstates/note_block.json")!=null,"Reserved block models generated");
            for(String name:ExpandedGear.BLOCKS)
                c.assertTrue(zip.getEntry("assets/convergence/models/block/"+name+".json")!=null,"Block model "+name);
        } catch(java.io.IOException error) { throw new RuntimeException(error); }
        c.succeed();
    }
    @GameTest public void crossplayAllItemsRoundTrip(GameTestHelper c) {
        var p=c.makeMockServerPlayerInLevel();
        var context=p.connection.getPacketContext();
        for(var entry:CrossplaySupport.BASES.entrySet()) {
            var original=gear(entry.getKey());
            original.setCount(Math.min(17,original.getMaxStackSize()));
            original.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("My "+entry.getKey()));
            if(entry.getKey().equals("sword"))original.enchant(c.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS),4);
            if(original.isDamageableItem())original.setDamageValue(7);
            var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(original,context,c.getLevel().registryAccess());
            c.assertValueEqual(wire.getItem(),entry.getValue(),"Vanilla network item: "+entry.getKey());
            c.assertValueEqual(wire.get(DataComponents.ITEM_MODEL),entry.getValue().components().get(DataComponents.ITEM_MODEL),"Java without a resource pack gets a complete native icon");
            var restored=eu.pb4.polymer.core.api.item.PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
            c.assertTrue(ItemStack.isSameItemSameComponents(original,restored),"Round-trip preserves components: "+entry.getKey());
            c.assertValueEqual(restored.getCount(),original.getCount(),"Round-trip count");
        }
        c.succeed();
    }
    @GameTest public void crossplayBlocksHaveDistinctVanillaStates(GameTestHelper c) {
        var seen=new java.util.HashSet<String>();
        var p=c.makeMockServerPlayerInLevel();
        var context=p.connection.getPacketContext();
        for(String name:ExpandedGear.BLOCKS) {
            var real=BuiltInRegistries.BLOCK.getValue(CrossplaySupport.id(name)).defaultBlockState();
            var wire=eu.pb4.polymer.core.api.block.PolymerBlockUtils.getPolymerBlockState(real,context);
            c.assertValueEqual(BuiltInRegistries.BLOCK.getKey(wire.getBlock()).getNamespace(),"minecraft","Block must be vanilla on wire");
            c.assertTrue(seen.add(CrossplaySupport.stateIdentifier(wire)),"Each block needs its own visual state");
            c.assertValueEqual(real.getLightEmission(),ExpandedGear.light(name),"Real server light remains intact");
        }
        c.succeed();
    }
    @GameTest public void crossplayPowerCommandsRestoreSneakingAndEnforceItems(GameTestHelper c) {
        var p=survival(c);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("sword"));p.setShiftKeyDown(false);
        int before=Convergence.state(p).swordMode;
        c.assertValueEqual(CrossplaySupport.usePower(p,true),1,"Alternate power accepted");
        c.assertValueEqual(Convergence.state(p).swordMode,(before+1)%3,"Same native sword mode cycle");
        c.assertFalse(p.isShiftKeyDown(),"Command must restore crouch state");
        p.setShiftKeyDown(true);CrossplaySupport.usePower(p,false);
        c.assertTrue(p.isShiftKeyDown(),"Normal power must also restore crouch state");
        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIRT));
        c.assertValueEqual(CrossplaySupport.usePower(p,true),0,"No power without actual Infinity gear");
        p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("sword"));
        c.assertValueEqual(CrossplaySupport.usePower(p,true),0,"Spectators cannot cast");
        c.succeed();
    }
    @GameTest public void crossplaySwapPreservesStacks(GameTestHelper c) {
        var p=c.makeMockServerPlayerInLevel();
        var spear=gear("spear");spear.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep me"));
        var mace=gear("mace");
        p.setItemInHand(InteractionHand.MAIN_HAND,spear);p.setItemInHand(InteractionHand.OFF_HAND,mace);
        c.assertValueEqual(CrossplaySupport.swapHands(p),1,"Swap available without operator rights");
        c.assertTrue(p.getMainHandItem()==mace&&p.getOffhandItem()==spear,"Swap original stacks without copying or loss");
        CrossplaySupport.swapHands(p);
        c.assertTrue(p.getMainHandItem()==spear&&p.getOffhandItem()==mace,"Swapping twice restores inventory");
        c.succeed();
    }
    private ItemStack gear(String name) { return new ItemStack(Convergence.ITEMS.get("convergence:"+name)); }
    private LivingEntity target(GameTestHelper c, int totems) {
        var t=c.spawnWithNoFreeWill(EntityTypes.HUSK,3,2,3);
        t.setNoAi(true); t.setNoGravity(true);
        if(totems>0)t.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        if(totems>1)t.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        return t;
    }
    private ServerPlayer player(GameTestHelper c, LivingEntity t) {
        var p=c.makeMockServerPlayerInLevel();
        p.setPos(t.getX()-2,t.getY(),t.getZ());p.setNoGravity(true);p.setOnGround(false);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("mace"));p.setItemInHand(InteractionHand.OFF_HAND,gear("spear"));
        p.setDeltaMovement(0,-1,0);p.lookAt(Anchor.EYES,t.getEyePosition());
        Convergence.state(p).drop=4;
        return p;
    }
    private void armored(GameTestHelper c, LivingEntity t) {
        var protection=c.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        Item[] armor={Items.NETHERITE_HELMET,Items.NETHERITE_CHESTPLATE,Items.NETHERITE_LEGGINGS,Items.NETHERITE_BOOTS};
        EquipmentSlot[] slots={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        for(int i=0;i<4;i++){var stack=new ItemStack(armor[i]);stack.enchant(protection,4);t.setItemSlot(slots[i],stack);}
        if(t instanceof ServerPlayer sp)sp.doTick();else t.tick();t.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,200,3));
        c.assertTrue(t.getArmorValue()>=20,"Real netherite armor must be active");
    }
    @GameTest public void equipmentAndNativeGlider(GameTestHelper c) {
        c.assertValueEqual(Convergence.ITEMS.size(),40,"Original 23 items, six blocks, eight cosmetic armor pieces, two wands, and backpack");
        c.assertTrue(gear("chestplate").has(DataComponents.GLIDER),"Winged armor must enable native flight");
        for(String name:new String[]{"helmet","chestplate","leggings","boots","sword","mace","spear"})
            c.assertTrue(gear(name).has(DataComponents.UNBREAKABLE),"Gear must remain unbreakable: "+name);
        c.assertTrue(gear("mace").is(TagKey.create(Registries.ITEM,Identifier.withDefaultNamespace("enchantable/mace"))),"Mace enchantment tags must load");
        c.succeed();
    }
    @GameTest public void fullSetKeepsPowersWithoutSlowFalling(GameTestHelper c) {
        var p=player(c,target(c,0));
        p.setItemSlot(EquipmentSlot.HEAD,gear("helmet"));p.setItemSlot(EquipmentSlot.CHEST,gear("chestplate"));
        p.setItemSlot(EquipmentSlot.LEGS,gear("leggings"));p.setItemSlot(EquipmentSlot.FEET,gear("boots"));
        Convergence.armor(p);
        c.assertTrue(p.hasEffect(MobEffects.HEALTH_BOOST),"Full set has extra health");
        c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getAmplifier(),3,"Resistance IV");
        c.assertFalse(p.hasEffect(MobEffects.SLOW_FALLING),"Never grant Slow Falling");c.succeed();
    }
    @GameTest(maxTicks=30) public void actualUseSpearTotemMaceAgainstProtectionArmor(GameTestHelper c) {
        var t=target(c,1);armored(c,t);var p=player(c,t);
        p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        c.assertValueEqual(Convergence.state(p).armed-Convergence.clock,200L,"Use must arm for ten seconds");
        Convergence.updateCombo(p);
        c.assertTrue(t.isAlive(),"Native totem must save the first hit");
        c.assertTrue(t.getOffhandItem().isEmpty(),"Minecraft itself must consume the offhand totem");
        c.assertFalse(Convergence.finish(p,t),"Cannot deliver second hit in the spear tick");
        p.setDeltaMovement(Vec3.ZERO);
        c.runAfterDelay(3,()->{c.assertFalse(t.isAlive(),"Mace must finish one-totem protected target on a later tick");c.succeed();});
    }
    @GameTest(maxTicks=30) public void secondNativeTotemStillSavesTarget(GameTestHelper c) {
        var t=target(c,2);var p=player(c,t);c.assertTrue(Convergence.thrust(p,t),"Charged first hit");p.setDeltaMovement(Vec3.ZERO);
        c.runAfterDelay(3,()->{
            c.assertTrue(t.isAlive(),"Second native totem must remain effective");
            c.assertTrue(t.getMainHandItem().isEmpty()&&t.getOffhandItem().isEmpty(),"Both totems consumed by Minecraft");
            c.assertTrue(Convergence.state(p).target==null,"Successful sequence cannot schedule a third hit");c.succeed();
        });
    }
    @GameTest(maxTicks=30) public void manualFinisherWaitsOneTick(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);p.setItemInHand(InteractionHand.MAIN_HAND,gear("spear"));p.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
        c.assertTrue(Convergence.thrust(p,t),"Manual spear must land");p.setItemInHand(InteractionHand.MAIN_HAND,gear("mace"));p.setDeltaMovement(Vec3.ZERO);
        c.assertFalse(Convergence.finish(p,t),"Manual path cannot bypass timing guard");
        c.runAfterDelay(2,()->{c.assertTrue(Convergence.finish(p,t),"Manual finisher allowed on later tick");c.assertFalse(t.isAlive(),"Finisher lethal after native totem");c.succeed();});
    }
    @GameTest(maxTicks=30) public void leavingMaceRangePreventsRemoteFinisher(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);p.setPos(t.getX()-5.5,t.getY(),t.getZ());p.setDeltaMovement(Vec3.ZERO);
        c.runAfterDelay(3,()->{c.assertTrue(t.isAlive(),"No remote mace hit outside 4.5 blocks");c.assertTrue(Convergence.state(p).target!=null,"Can close the gap during the live window");c.succeed();});
    }
    @GameTest(maxTicks=30) public void changingOffhandCancelsAutomaticCombo(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);p.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);p.setDeltaMovement(Vec3.ZERO);
        c.runAfterDelay(2,()->{c.assertTrue(t.isAlive(),"Weapon change prevents finisher");c.assertTrue(Convergence.state(p).target==null,"Stale mark removed");c.succeed();});
    }
    @GameTest public void pendingSpearMarkCannotBeOverwritten(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);
        c.assertFalse(Convergence.arm(p),"Rearm cannot overwrite a mark");
        c.assertFalse(Convergence.thrust(p,t),"Repeated spear cannot overwrite a mark");c.succeed();
    }
    @GameTest public void groundedMeleeUsesNativeAttributes(GameTestHelper c) {
        for(String name:new String[]{"mace","spear","sword"}){
            var t=target(c,1);var p=player(c,t);p.setItemInHand(InteractionHand.MAIN_HAND,gear(name));p.setOnGround(true);p.setDeltaMovement(Vec3.ZERO);Convergence.state(p).drop=0;
            p.doTick();
            var result=AttackEntityCallback.EVENT.invoker().interact(p,c.getLevel(),InteractionHand.MAIN_HAND,t,null);
            c.assertTrue(result==InteractionResult.PASS,"Grounded "+name+" must allow vanilla attack");
            c.assertTrue(p.getAttributeValue(Attributes.ATTACK_DAMAGE)>30,"Weapon attributes must actually be equipped: "+name+" = "+p.getAttributeValue(Attributes.ATTACK_DAMAGE));
            float before=t.getHealth();p.attack(t);
            c.assertTrue(t.getHealth()<before||t.getOffhandItem().isEmpty(),"Native "+name+" must damage the target");
        }
        c.succeed();
    }
    @GameTest public void poweredMaceHasCooldownAndOneDamagePath(GameTestHelper c) {
        var t=target(c,2);var p=player(c,t);p.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
        c.assertTrue(Convergence.melee(p,t)==InteractionResult.SUCCESS,"Powered smash consumes vanilla attack");
        c.assertTrue(Convergence.state(p).cooldown.get("melee_smash")>Convergence.clock,"Smash gets a cooldown");
        c.assertTrue(Convergence.melee(p,t)==InteractionResult.PASS,"Cooldown prevents repeated scripted damage; vanilla remains available");
        c.assertTrue(t.isAlive(),"Only one scripted hit was applied");c.succeed();
    }
    @GameTest public void onlyActualInfinityBootsCancelFalls(GameTestHelper c) {
        var p=player(c,target(c,0));var source=c.getLevel().damageSources().fall();
        p.setItemSlot(EquipmentSlot.FEET,gear("helmet"));
        c.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p,source,20),"Other custom gear is not fall protection");
        p.setItemSlot(EquipmentSlot.FEET,gear("boots"));
        c.assertFalse(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p,source,20),"Actual boots cancel fall damage");c.succeed();
    }
    @GameTest public void blinkClearsFallAndComboState(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);p.setItemInHand(InteractionHand.MAIN_HAND,gear("sword"));
        p.setYRot(0);p.setXRot(0);p.setShiftKeyDown(false);p.fallDistance=25;
        var s=Convergence.state(p);s.swordMode=1;s.armed=Convergence.clock+10;s.target=t.getUUID();s.drop=25;
        Vec3 before=p.position();Convergence.swordPower(p);
        c.assertTrue(p.position().distanceTo(before)>=2,"Blink actually moved the player");
        c.assertValueEqual(p.fallDistance,0.0,"Blink resets engine fall distance");
        c.assertValueEqual(p.getDeltaMovement(),Vec3.ZERO,"Blink resets velocity");
        c.assertTrue(s.target==null&&s.armed==0&&s.drop==0,"Blink clears stale combo charge");c.succeed();
    }
    @GameTest public void pendingComboAllowsOtherTargetsNativeMelee(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);Convergence.thrust(p,t);
        var other=c.spawnWithNoFreeWill(EntityTypes.HUSK,3,2,5);other.setNoAi(true);
        c.assertTrue(Convergence.melee(p,other)==InteractionResult.PASS,"Unrelated target must retain vanilla melee while mark is pending");
        c.assertTrue(Convergence.melee(p,t)==InteractionResult.SUCCESS,"Same-tick marked target must not get an extra native hit");
        c.assertTrue(t.isAlive(),"Mandatory delay protects the original target until next tick");c.succeed();
    }
    @GameTest public void rejectedSpearKeepsBoundedArmWithoutSpendingCooldown(GameTestHelper c) {
        var t=target(c,1);var p=player(c,t);p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);t.setPermanentlyInvulnerable(true);Convergence.arm(p);
        c.assertFalse(Convergence.thrust(p,t),"Invulnerable target rejects damage");
        c.assertTrue(Convergence.state(p).armed>Convergence.clock,"Transient rejection retains the bounded arm for retry");
        c.assertTrue(Convergence.state(p).target==null,"Rejected hit cannot open a follow-up");
        c.assertFalse(Convergence.state(p).cooldown.containsKey("spear"),"Failed hit costs no cooldown");c.succeed();
    }
    @GameTest public void protectedTargetsAreExcluded(GameTestHelper c) {
        var t=target(c,0);var p=player(c,t);t.addTag("convergence_friend");
        c.assertFalse(Convergence.hurt(p,t,3000),"Friendly target cannot receive scripted hits");
        var creative=c.makeMockServerPlayerInLevel();
        c.assertFalse(Convergence.valid(p,creative),"Creative target excluded");c.succeed();
    }
    private ServerPlayer survival(GameTestHelper c) {
        var profile=new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"combo-survival");
        var data=net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false);
        var p=new ServerPlayer(c.getLevel().getServer(),c.getLevel(),profile,data.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,data);
        p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.setNoGravity(true);
        p.setPermanentlyInvulnerable(false);p.getAbilities().invulnerable=false;
        c.assertFalse(p.isCreative(),"Real Survival player required, not creative-only mock");
        return p;
    }
    @GameTest(maxTicks=40) public void survivalPlayerTotemCombo(GameTestHelper c) {
        var t=survival(c);var pos=c.absolutePos(new net.minecraft.core.BlockPos(3,3,3));
        t.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);armored(c,t);
        t.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        var p=survival(c);p.setPos(t.getX()-2,t.getY(),t.getZ());p.setOnGround(false);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("mace"));p.setItemInHand(InteractionHand.OFF_HAND,gear("spear"));
        p.setDeltaMovement(0,-1,0);p.lookAt(Anchor.EYES,t.getEyePosition());
        p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        Convergence.updateCombo(p);p.setDeltaMovement(Vec3.ZERO);
        c.assertTrue(t.isAlive(),"Native totem saves actual Survival target from spear");
        c.assertTrue(t.getOffhandItem().isEmpty(),"Native Survival totem consumed");
        c.runAfterDelay(3,()->{c.assertFalse(t.isAlive(),"Automatic mace finishes real armored Survival player");c.succeed();});
    }
    @GameTest(maxTicks=40) public void delayedMeleeAfterComboHasNoThirdScriptedHit(GameTestHelper c) {
        var t=target(c,2);var p=player(c,t);Convergence.thrust(p,t);p.setDeltaMovement(Vec3.ZERO);
        c.runAfterDelay(3,()->{
            c.assertTrue(t.isAlive(),"Second totem saves target");p.setOnGround(false);p.setDeltaMovement(0,-1,0);
            c.assertTrue(Convergence.melee(p,t)==InteractionResult.PASS,"Delayed swing must not append scripted smash");
            c.assertTrue(t.isAlive(),"No third scripted hit");c.succeed();
        });
    }
    @GameTest public void ingotDoesNotConsumeUse(GameTestHelper c) {
        var p=player(c,target(c,0));p.setItemInHand(InteractionHand.MAIN_HAND,gear("ingot"));
        c.assertTrue(p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND)==InteractionResult.PASS,"Ingot must allow ordinary item Use");c.succeed();
    }

    @GameTest public void survivalChestplateStartsNativeGlide(GameTestHelper c) {
        var p=survival(c);var pos=c.absolutePos(new net.minecraft.core.BlockPos(1,4,1));
        p.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);p.setOnGround(false);
        p.setItemSlot(EquipmentSlot.CHEST,gear("chestplate"));p.setDeltaMovement(0,-0.4,0);
        c.assertTrue(p.tryToStartFallFlying(),"Native player must be able to start gliding with Infinity chestplate");
        c.assertTrue(p.isFallFlying(),"Native gliding flag must be active");c.succeed();
    }
    @GameTest public void stormDoesNotDoubleHitSplashTargets(GameTestHelper c) {
        var primary=target(c,2);var secondary=c.spawnWithNoFreeWill(EntityTypes.HUSK,3,2,5);secondary.setNoAi(true);
        secondary.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        secondary.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        var p=player(c,primary);p.setItemInHand(InteractionHand.MAIN_HAND,gear("sword"));Convergence.swordPower(p);
        c.assertTrue(secondary.isAlive(),"Splash target should survive native totem");
        int remaining=(secondary.getMainHandItem().is(Items.TOTEM_OF_UNDYING)?1:0)+(secondary.getOffhandItem().is(Items.TOTEM_OF_UNDYING)?1:0);
        c.assertValueEqual(remaining,1,"Splash/shockwave overlap must produce exactly one hit");c.succeed();
    }

    @GameTest(maxTicks=20) public void spearAndMaceUseDistinctServerTicks(GameTestHelper c) {
        var t=target(c,2);var p=player(c,t);Convergence.arm(p);Convergence.updateCombo(p);
        long spearTick=c.getLevel().getServer().getTickCount();long abilityTick=Convergence.clock;
        p.setDeltaMovement(Vec3.ZERO);
        // Running end-tick work in the same tick must not advance the ability clock.
        Convergence.tick(c.getLevel().getServer());
        c.assertValueEqual(Convergence.clock,abilityTick,"End-tick work must not advance the clock");
        c.assertTrue(Convergence.state(p).target!=null,"No finisher during the first server tick");
        c.runAfterDelay(2,()->{
            c.assertTrue(c.getLevel().getServer().getTickCount()>spearTick,"Server time actually advanced");
            c.assertTrue(Convergence.state(p).target==null,"Automatic finisher completes on later server tick");c.succeed();
        });
    }

    private ServerPlayer toolPlayer(GameTestHelper c,String name,net.minecraft.core.BlockPos at) {
        var p=survival(c);p.setPos(at.getX()+0.5,at.getY()+3,at.getZ()+0.5);
        p.setDeltaMovement(Vec3.ZERO);p.setItemInHand(InteractionHand.MAIN_HAND,gear(name));
        p.lookAt(Anchor.EYES,Vec3.atCenterOf(at));return p;
    }
    @GameTest public void poweredToolsHaveNativeMiningAndEnchantments(GameTestHelper c) {
        for(String name:new String[]{"pickaxe","axe","shovel","hoe"}) {
            var stack=gear(name);
            c.assertTrue(stack.has(DataComponents.UNBREAKABLE),"Unbreakable "+name);
            c.assertTrue(stack.has(DataComponents.TOOL),"Native tool rules "+name);
            c.assertTrue(stack.hasFoil(),"Visible enchanted tool "+name);
            c.assertTrue(stack.is(TagKey.create(Registries.ITEM,Identifier.withDefaultNamespace("enchantable/mining"))),"Mining enchantments "+name);
        }
        c.assertTrue(gear("pickaxe").isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState()),"Pickaxe can harvest diamond ore");
        c.assertTrue(gear("pickaxe").getDestroySpeed(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())>=40,"Powered native mining speed");c.succeed();
    }
    @GameTest public void pickaxeExcavatesOnlyItsNineBlockPlane(GameTestHelper c) {
        var at=c.absolutePos(new net.minecraft.core.BlockPos(2,1,2));var w=c.getLevel();
        for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP))w.setBlockAndUpdate(q,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        w.setBlockAndUpdate(at.offset(1,0,1),net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState());
        w.setBlockAndUpdate(at.below(),net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState());
        var p=toolPlayer(c,"pickaxe",at);
        c.assertValueEqual(PoweredTools.use(p,"convergence:pickaxe",at,net.minecraft.core.Direction.UP),8,"Eight stone blocks excavated around bedrock");
        c.assertTrue(w.getBlockState(at.offset(1,0,1)).is(net.minecraft.world.level.block.Blocks.BEDROCK),"Bedrock untouched");
        c.assertTrue(w.getBlockState(at.below()).is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE),"Next layer untouched");
        w.setBlockAndUpdate(at,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        c.assertValueEqual(PoweredTools.use(p,"convergence:pickaxe",at,net.minecraft.core.Direction.UP),0,"Cooldown prevents repeat excavation");c.succeed();
    }
    @GameTest public void shovelSkipsStoneAndAdventureCannotExcavate(GameTestHelper c) {
        var at=c.absolutePos(new net.minecraft.core.BlockPos(2,1,2));var w=c.getLevel();
        for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP))w.setBlockAndUpdate(q,net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
        w.setBlockAndUpdate(at.offset(1,0,1),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var p=toolPlayer(c,"shovel",at);p.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
        c.assertValueEqual(PoweredTools.use(p,"convergence:shovel",at,net.minecraft.core.Direction.UP),0,"Adventure cannot use terrain powers");
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var hit=new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(at),net.minecraft.core.Direction.UP,at,false);
        var result=p.getMainHandItem().getItem().useOn(new net.minecraft.world.item.context.UseOnContext(p,InteractionHand.MAIN_HAND,hit));
        c.assertTrue(result==InteractionResult.SUCCESS,"Actual block Use dispatches shovel power");
        int removed=0;for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP))if(w.getBlockState(q).isAir())removed++;
        c.assertValueEqual(removed,8,"Only soil excavated");
        c.assertTrue(w.getBlockState(at.offset(1,0,1)).is(net.minecraft.world.level.block.Blocks.STONE),"Stone untouched");c.succeed();
    }
    @GameTest public void timberIsBoundedAndLeavesOtherSpecies(GameTestHelper c) {
        var at=c.absolutePos(new net.minecraft.core.BlockPos(2,1,2));var w=c.getLevel();
        for(int x=0;x<5;x++)for(int z=0;z<7;z++)w.setBlockAndUpdate(at.offset(x,0,z),net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        w.setBlockAndUpdate(at.offset(-1,0,0),net.minecraft.world.level.block.Blocks.BIRCH_LOG.defaultBlockState());
        var p=toolPlayer(c,"axe",at);
        c.assertValueEqual(PoweredTools.use(p,"convergence:axe",at,net.minecraft.core.Direction.UP),32,"Timber capped at 32 logs");
        int remaining=0;for(int x=0;x<5;x++)for(int z=0;z<7;z++)if(w.getBlockState(at.offset(x,0,z)).is(net.minecraft.world.level.block.Blocks.OAK_LOG))remaining++;
        c.assertValueEqual(remaining,3,"Only 32 of 35 logs felled");
        c.assertTrue(w.getBlockState(at.offset(-1,0,0)).is(net.minecraft.world.level.block.Blocks.BIRCH_LOG),"Other species remains");c.succeed();
    }
    @GameTest public void farmBloomConsumesSeedsAndGrowsWheat(GameTestHelper c) {
        var at=c.absolutePos(new net.minecraft.core.BlockPos(2,1,2));var w=c.getLevel();
        for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP))w.setBlockAndUpdate(q,net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
        var p=toolPlayer(c,"hoe",at);p.getInventory().setItem(1,new ItemStack(Items.WHEAT_SEEDS,3));
        c.assertValueEqual(PoweredTools.use(p,"convergence:hoe",at,net.minecraft.core.Direction.UP),12,"Nine tilled and three planted");
        c.assertTrue(p.getInventory().getItem(1).isEmpty(),"Survival planting consumes exactly three seeds");
        int crops=0;for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP)) {
            c.assertTrue(w.getBlockState(q).is(net.minecraft.world.level.block.Blocks.FARMLAND),"All soil tilled");
            if(w.getBlockState(q.above()).is(net.minecraft.world.level.block.Blocks.WHEAT))crops++;
        }
        c.assertValueEqual(crops,3,"No crops created without seeds");
        PoweredTools.farm(p,at);
        c.assertValueEqual(w.getBlockState(at.above()).getValue(net.minecraft.world.level.block.CropBlock.AGE),7,"Existing wheat matures");c.succeed();
    }
    @GameTest public void secondaryToolsAffectHostilesAndRenewalHeals(GameTestHelper c) {
        var hostile=target(c,2);var p=player(c,hostile);p.setItemInHand(InteractionHand.MAIN_HAND,gear("pickaxe"));p.setDeltaMovement(Vec3.ZERO);
        var friend=c.spawnWithNoFreeWill(EntityTypes.HUSK,3,2,4);friend.setNoAi(true);friend.addTag("convergence_friend");
        float friendHealth=friend.getHealth();p.setShiftKeyDown(true);p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        c.assertTrue(hostile.getDeltaMovement().x<0,"Gravity Well pulls hostile toward player");
        c.assertValueEqual(friend.getHealth(),friendHealth,"Friendly tagged entity is unharmed");
        c.assertTrue(friend.getDeltaMovement().lengthSqr()==0,"Friendly tagged entity is not moved");
        var healer=survival(c);healer.setItemInHand(InteractionHand.MAIN_HAND,gear("hoe"));healer.setHealth(5);healer.igniteForSeconds(10);
        healer.setShiftKeyDown(true);healer.getMainHandItem().getItem().use(c.getLevel(),healer,InteractionHand.MAIN_HAND);
        c.assertValueEqual(healer.getHealth(),healer.getMaxHealth(),"Renewal restores health");
        c.assertFalse(healer.isOnFire(),"Renewal extinguishes fire");
        c.assertTrue(healer.hasEffect(MobEffects.REGENERATION),"Renewal grants regeneration");
        healer.setHealth(5);PoweredTools.combat(healer,"convergence:hoe");c.assertValueEqual(healer.getHealth(),5.0f,"Renewal cooldown enforced");c.succeed();
    }

    @GameTest public void infinityBlocksRegisterWithLightAndNativeMining(GameTestHelper c) {
        for(String path:ExpandedGear.BLOCKS) {
            var block=BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("convergence",path));
            c.assertTrue(!block.defaultBlockState().isAir(),"Registered block: "+path);
            c.assertTrue(Convergence.ITEMS.get("convergence:"+path)!=null,"Placeable block item: "+path);
            c.assertTrue(gear("pickaxe").isCorrectToolForDrops(block.defaultBlockState()),"Native pickaxe harvests "+path);
        }
        c.assertValueEqual(BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("convergence","radiant_infinity")).defaultBlockState().getLightEmission(),15,"Radiant block emits full light");
        c.succeed();
    }
    @GameTest public void pickaxeExcavatesOnlyTheFourCustomBlocks(GameTestHelper c) {
        var at=c.absolutePos(new net.minecraft.core.BlockPos(2,1,2));var w=c.getLevel();
        for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP))w.setBlockAndUpdate(q,BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("convergence","infinity_block")).defaultBlockState());
        var p=toolPlayer(c,"pickaxe",at);
        c.assertValueEqual(PoweredTools.use(p,"convergence:pickaxe",at,net.minecraft.core.Direction.UP),9,"All nine Infinity blocks excavated");
        for(var q:PoweredTools.plane(at,net.minecraft.core.Direction.UP))c.assertTrue(w.getBlockState(q).isAir(),"Custom blocks removed natively");
        c.succeed();
    }
    @GameTest public void customTotemSavesLethalDamageButNotKillCommands(GameTestHelper c) {
        var p=survival(c);p.setItemInHand(InteractionHand.OFF_HAND,gear("totem"));
        c.assertTrue(p.getOffhandItem().has(DataComponents.DEATH_PROTECTION),"Custom totem uses native death protection");
        p.hurtServer(c.getLevel(),c.getLevel().damageSources().generic(),1000.0F);
        c.assertTrue(p.isAlive(),"Custom totem saves actual lethal damage");
        c.assertTrue(p.getOffhandItem().isEmpty(),"Exactly one custom totem consumed");
        c.assertTrue(p.hasEffect(MobEffects.REGENERATION),"Rescue grants regeneration");
        c.assertTrue(p.hasEffect(MobEffects.FIRE_RESISTANCE),"Rescue grants fire resistance");
        var bypass=survival(c);bypass.setItemInHand(InteractionHand.OFF_HAND,gear("totem"));
        var kill=c.getLevel().damageSources().genericKill();
        c.assertTrue(kill.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY),"Kill source has native death-protection bypass tag");
        bypass.hurtServer(c.getLevel(),kill,1000.0F);
        c.assertFalse(bypass.isAlive(),"Kill bypasses custom totem rescue");
        c.succeed();
    }
    @GameTest public void nativeTotemHandOrderIncludesCustomTotem(GameTestHelper c) {
        var p=survival(c);p.setItemInHand(InteractionHand.MAIN_HAND,gear("totem"));
        p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        p.hurtServer(c.getLevel(),c.getLevel().damageSources().generic(),1000.0F);
        c.assertTrue(p.isAlive(),"A held totem handles fatal damage");
        c.assertTrue(p.getMainHandItem().isEmpty(),"Native hand order consumes custom mainhand first");
        c.assertTrue(p.getOffhandItem().is(Items.TOTEM_OF_UNDYING),"Vanilla offhand totem retained");
        var vanilla=survival(c);vanilla.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        vanilla.setItemInHand(InteractionHand.OFF_HAND,gear("totem"));
        vanilla.hurtServer(c.getLevel(),c.getLevel().damageSources().generic(),1000.0F);
        c.assertTrue(vanilla.getMainHandItem().isEmpty(),"Vanilla mainhand consumed first");
        c.assertTrue(vanilla.getOffhandItem().is(Convergence.ITEMS.get("convergence:totem")),"Custom offhand retained");
        c.succeed();
    }
    @GameTest(maxTicks=40) public void customTotemSavesSpearButMaceFinishesCombo(GameTestHelper c) {
        var t=survival(c);var pos=c.absolutePos(new net.minecraft.core.BlockPos(3,3,3));
        t.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5);armored(c,t);
        t.setItemInHand(InteractionHand.OFF_HAND,gear("totem"));
        var p=survival(c);p.setPos(t.getX()-2,t.getY(),t.getZ());p.setOnGround(false);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("mace"));p.setItemInHand(InteractionHand.OFF_HAND,gear("spear"));
        p.setDeltaMovement(0,-1,0);p.lookAt(Anchor.EYES,t.getEyePosition());
        p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        Convergence.updateCombo(p);p.setDeltaMovement(Vec3.ZERO);
        c.assertTrue(t.isAlive(),"Custom totem saves actual Survival target from spear");
        c.assertTrue(t.getOffhandItem().isEmpty(),"Custom totem consumed by first hit");
        c.runAfterDelay(3,()->{c.assertFalse(t.isAlive(),"Automatic mace finishes after custom totem rescue");c.succeed();});
    }
    @GameTest public void shieldUsesNativeBlockAndCrouchWard(GameTestHelper c) {
        var p=survival(c);p.setItemInHand(InteractionHand.MAIN_HAND,gear("shield"));
        c.assertTrue(p.getMainHandItem().has(DataComponents.BLOCKS_ATTACKS),"Native shield defense component retained");
        p.setShiftKeyDown(false);
        p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        c.assertTrue(p.isUsingItem(),"Ordinary Use starts native blocking");
        p.releaseUsingItem();p.setShiftKeyDown(true);
        p.getMainHandItem().getItem().use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        c.assertTrue(p.hasEffect(MobEffects.RESISTANCE),"Crouch Use grants Ward resistance");
        c.assertTrue(p.hasEffect(MobEffects.ABSORPTION),"Crouch Use grants Ward absorption");
        c.succeed();
    }
    @GameTest public void nativeBowConsumesCustomArrowAndKeepsProjectileType(GameTestHelper c) {
        var p=survival(c);var at=c.absolutePos(new net.minecraft.core.BlockPos(2,2,2));p.setPos(Vec3.atCenterOf(at));
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("bow"));
        var ammo=new ItemStack(Convergence.ITEMS.get("convergence:infinity_arrow"),3);p.getInventory().setItem(1,ammo);
        var bow=(net.minecraft.world.item.BowItem)p.getMainHandItem().getItem();
        c.assertTrue(bow.getAllSupportedProjectiles().test(ammo),"Custom arrows are native bow ammunition");
        int left=bow.getUseDuration(p.getMainHandItem(),p)-40;
        c.assertTrue(bow.releaseUsing(p.getMainHandItem(),c.getLevel(),p,left),"Fully drawn bow fires");
        c.assertValueEqual(p.getInventory().getItem(1).getCount(),2,"Native bow consumes one custom arrow");
        var arrows=c.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,p.getBoundingBox().inflate(5),e->true);
        c.assertTrue(arrows.stream().anyMatch(e->e.getPickupItemStackOrigin().is(Convergence.ITEMS.get("convergence:infinity_arrow"))),"Fired projectile retains custom arrow type");
        c.assertTrue(((net.minecraft.world.item.CrossbowItem)gear("crossbow").getItem()).getAllSupportedProjectiles().test(gear("void_arrow")),"Crossbow accepts Void ammo");
        c.succeed();
    }
    @GameTest public void nativeCrossbowLoadsAndFiresCustomArrow(GameTestHelper c) {
        var p=survival(c);var at=c.absolutePos(new net.minecraft.core.BlockPos(2,2,2));p.setPos(Vec3.atCenterOf(at));
        var weapon=gear("crossbow");p.setItemInHand(InteractionHand.MAIN_HAND,weapon);
        p.getInventory().setItem(1,new ItemStack(Convergence.ITEMS.get("convergence:void_arrow"),3));
        var crossbow=(net.minecraft.world.item.CrossbowItem)weapon.getItem();
        c.assertFalse(net.minecraft.world.item.CrossbowItem.isCharged(weapon),"Custom crossbow starts unloaded");
        int remaining=weapon.getUseDuration(p)-net.minecraft.world.item.CrossbowItem.getChargeDuration(weapon,p)-1;
        crossbow.onUseTick(c.getLevel(),p,weapon,remaining);
        c.assertTrue(net.minecraft.world.item.CrossbowItem.isCharged(weapon),"Native crossbow loads custom Void ammo");
        c.assertValueEqual(p.getInventory().getItem(1).getCount(),2,"Loading consumes exactly one Void arrow");
        crossbow.use(c.getLevel(),p,InteractionHand.MAIN_HAND);
        c.assertFalse(net.minecraft.world.item.CrossbowItem.isCharged(weapon),"Firing clears native charge");
        var arrows=c.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,p.getBoundingBox().inflate(5),e->true);
        c.assertTrue(arrows.stream().anyMatch(e->e.getPickupItemStackOrigin().is(Convergence.ITEMS.get("convergence:void_arrow"))),"Fired projectile retains Void type");
        c.succeed();
    }
    @GameTest public void arrowEffectsRespectFriendsAndTriggerOnKillingHit(GameTestHelper c) {
        var target=target(c,0);var p=survival(c);p.setPos(target.getX()-2,target.getY(),target.getZ());
        var nearby=c.spawnWithNoFreeWill(EntityTypes.HUSK,3,2,5);nearby.setNoAi(true);nearby.setNoGravity(true);
        var friend=c.spawnWithNoFreeWill(EntityTypes.HUSK,4,2,3);friend.setNoAi(true);friend.setNoGravity(true);friend.addTag("convergence_friend");
        var damageType=c.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(Convergence.COMBO);
        var infinity=new net.minecraft.world.entity.projectile.arrow.Arrow(c.getLevel(),p,gear("infinity_arrow"),gear("bow"));
        var radiant=new net.minecraft.world.damagesource.DamageSource(damageType,infinity,p);
        float initial=target.getHealth();
        ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(target,radiant,5,5,false);
        c.assertTrue(target.getHealth()<=initial-12,"Infinity arrow adds radiant damage");
        c.assertTrue(target.hasEffect(MobEffects.GLOWING),"Infinity arrow reveals the target");
        var voidArrow=new net.minecraft.world.entity.projectile.arrow.Arrow(c.getLevel(),p,gear("void_arrow"),gear("bow"));
        var voidSource=new net.minecraft.world.damagesource.DamageSource(damageType,voidArrow,p);
        target.setHealth(20);target.setDeltaMovement(Vec3.ZERO);
        ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(target,voidSource,5,5,false);
        c.assertTrue(target.hasEffect(MobEffects.SLOWNESS),"Void arrow slows");
        c.assertTrue(target.getDeltaMovement().x<0,"Void arrow pulls toward shooter");
        var fireArrow=new net.minecraft.world.entity.projectile.arrow.Arrow(c.getLevel(),p,gear("starfire_arrow"),gear("bow"));
        var fireSource=new net.minecraft.world.damagesource.DamageSource(damageType,fireArrow,p);
        float before=nearby.getHealth(),friendBefore=friend.getHealth();target.setHealth(0);
        c.assertTrue(ServerLivingEntityEvents.ALLOW_DEATH.invoker().allowDeath(target,fireSource,20),"Killing hit still permits death");
        c.assertTrue(nearby.getHealth()<before,"Killing Starfire hit bursts onto nearby hostile");
        c.assertValueEqual(friend.getHealth(),friendBefore,"Friendly target excluded from Starfire burst");
        c.succeed();
    }
}
