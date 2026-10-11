package dev.convergence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.component.Component;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DeathProtectionComponent;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.consume.ApplyEffectsConsumeEffect;
import net.minecraft.item.consume.ClearAllEffectsConsumeEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Additional gear uses native items, blocks, and projectile entities wherever possible. */
final class ExpandedGear {
   static final Set<String> NEW_BLOCKS = Set.of("aurora_tiles", "obsidian_lattice", "copper_circuit", "moonstone", "sunstone_lamp", "verdant_mosaic");
   static final Set<String> BLOCKS = Set.of("infinity_block", "polished_infinity", "infinity_bricks", "radiant_infinity",
      "aurora_tiles", "obsidian_lattice", "copper_circuit", "moonstone", "sunstone_lamp", "verdant_mosaic");
   static final Map<String,Item> COSMETIC_ARMOR = Map.of(
      "aurora_helmet",Items.LEATHER_HELMET,"aurora_chestplate",Items.LEATHER_CHESTPLATE,
      "aurora_leggings",Items.LEATHER_LEGGINGS,"aurora_boots",Items.LEATHER_BOOTS,
      "ember_helmet",Items.LEATHER_HELMET,"ember_chestplate",Items.LEATHER_CHESTPLATE,
      "ember_leggings",Items.LEATHER_LEGGINGS,"ember_boots",Items.LEATHER_BOOTS);
   static float hardness(String path) {return NEW_BLOCKS.contains(path)?3.0F:12.0F;}
   static int light(String path) {return path.equals("radiant_infinity")||path.equals("sunstone_lamp")?15:0;}
   static final Set<String> ARROWS = Set.of("infinity_arrow", "void_arrow", "starfire_arrow");

   private static Identifier id(String path) { return Identifier.of("convergence", path); }

   private static Item.Settings settings(String path, Item base, int maxCount, boolean unbreakable) {
      Item.Settings out = new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, id(path)));
      for (Component<?> component : base.getComponents()) {
         if (component.type() != DataComponentTypes.ITEM_NAME && component.type() != DataComponentTypes.ITEM_MODEL) {
            Convergence.copy(out, component);
         }
      }
      out.maxCount(maxCount);
      if (unbreakable) {
         out.maxDamage(32767);
         out.component(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
         out.component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
      return out;
   }

   private static void registerItem(String path, Item item) {
      Registry.register(Registries.ITEM, id(path), item);
      Convergence.ITEMS.put("convergence:" + path, item);
   }

   private static void registerBlock(String path, boolean radiant) {
      Identifier key = id(path);
      AbstractBlock.Settings settings = AbstractBlock.Settings.create()
         .registryKey(RegistryKey.of(RegistryKeys.BLOCK, key))
         .strength(hardness(path), NEW_BLOCKS.contains(path)?6.0F:1200.0F)
         .sounds(NEW_BLOCKS.contains(path)?BlockSoundGroup.STONE:BlockSoundGroup.METAL)
         .requiresTool()
         .lootTable(Optional.of(RegistryKey.of(RegistryKeys.LOOT_TABLE, id("blocks/" + path))));
      if (light(path)>0) settings.luminance(state -> light(path));
      Block block = Registry.register(Registries.BLOCK, key, new Block(settings));
      registerItem(path, new BlockItem(block, new Item.Settings()
         .registryKey(RegistryKey.of(RegistryKeys.ITEM, key))
         .useBlockPrefixedTranslationKey()
         .maxCount(64)));
   }

   static void register() {
      registerBlock("infinity_block", false);
      registerBlock("polished_infinity", false);
      registerBlock("infinity_bricks", false);
      registerBlock("radiant_infinity", true);
      for(String path:NEW_BLOCKS.stream().sorted().toList())registerBlock(path,light(path)>0);
      for(var entry:COSMETIC_ARMOR.entrySet())registerCosmeticArmor(entry.getKey(),entry.getValue());
      registerItem("builder_wand",new CreativeWand(settings("builder_wand",Items.BLAZE_ROD,1,false)));
      registerItem("sculptor_wand",new CreativeWand(settings("sculptor_wand",Items.STICK,1,false)));
      Item.Settings totem = settings("totem", Items.TOTEM_OF_UNDYING, 1, false);
      // Native death protection handles hand order, kill/void bypass, consumption,
      // statistics, criteria, and the totem animation. These effects are unique.
      totem.component(DataComponentTypes.DEATH_PROTECTION, new DeathProtectionComponent(List.of(
         ClearAllEffectsConsumeEffect.INSTANCE,
         new ApplyEffectsConsumeEffect(List.of(
            new StatusEffectInstance(StatusEffects.REGENERATION, 800, 2),
            new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0),
            new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 2),
            new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 1)
         ))
      )));
      registerItem("totem", new Item(totem));
      registerItem("shield", new WardShield(settings("shield", Items.SHIELD, 1, true)));
      registerItem("bow", new BowItem(settings("bow", Items.BOW, 1, true)));
      registerItem("crossbow", new CrossbowItem(settings("crossbow", Items.CROSSBOW, 1, true)));
      registerItem("infinity_arrow", new ArrowItem(settings("infinity_arrow", Items.ARROW, 64, false)));
      registerItem("void_arrow", new ArrowItem(settings("void_arrow", Items.ARROW, 64, false)));
      registerItem("starfire_arrow", new ArrowItem(settings("starfire_arrow", Items.ARROW, 64, false)));
   }

   private static void registerCosmeticArmor(String path,Item base) {
      var outfit=path.startsWith("aurora_")?"aurora":"ember";
      var slot=base.getComponents().get(DataComponentTypes.EQUIPPABLE).slot();
      RegistryKey<EquipmentAsset> asset=RegistryKey.of(RegistryKey.ofRegistry(Identifier.of("minecraft","equipment_asset")),id(outfit+"_armor"));
      var config=settings(path,base,1,false)
         .attributeModifiers(AttributeModifiersComponent.builder().build())
         .component(DataComponentTypes.EQUIPPABLE,EquippableComponent.builder(slot).model(asset).damageOnHurt(false).build())
         .component(DataComponentTypes.DYED_COLOR,new DyedColorComponent(outfit.equals("aurora")?0x48DCC8:0xF07A32))
         .component(DataComponentTypes.UNBREAKABLE,Unit.INSTANCE);
      registerItem(path,new Item(config));
   }

   private static final class CreativeWand extends Item {
      CreativeWand(Item.Settings settings){super(settings);}
      @Override public ActionResult useOnBlock(ItemUsageContext context) {
         if(context.getHand()!=Hand.MAIN_HAND)return ActionResult.PASS;
         if(context.getPlayer() instanceof ServerPlayerEntity p)
            PoweredTools.creativeUse(p,Convergence.id(context.getStack()),context.getBlockPos(),context.getSide());
         return ActionResult.SUCCESS;
      }
      @Override public ActionResult use(World world,PlayerEntity user,Hand hand) {
         if(hand!=Hand.MAIN_HAND)return ActionResult.PASS;
         if(user instanceof ServerPlayerEntity p)PoweredTools.creativeAimed(p,Convergence.id(user.getMainHandStack()));
         return ActionResult.SUCCESS;
      }
   }

   static void registerEvents() {
      ServerLivingEntityEvents.ALLOW_DEATH.register((living, source, amount) -> {
         arrowHit(living, source);
         return true;
      });
      ServerLivingEntityEvents.AFTER_DAMAGE.register((living, source, dealt, taken, blocked) -> {
         if (dealt > 0 && !blocked) arrowHit(living, source);
      });
   }

   private static void arrowHit(LivingEntity target, DamageSource source) {
      if (!(source.getSource() instanceof PersistentProjectileEntity projectile)
         || !(source.getAttacker() instanceof ServerPlayerEntity shooter)) return;
      String type = Convergence.id(projectile.getItemStack());
      if (!type.startsWith("convergence:") || !ARROWS.contains(type.substring("convergence:".length()))) return;
      // This also runs on fatal hits, when LivingEntity.isAlive() is already false.
      if (target == shooter || target.getEntityWorld() != shooter.getEntityWorld()
         || target instanceof ArmorStandEntity || target.getCommandTags().contains("convergence_friend")
         || target instanceof TameableEntity tameable && tameable.isTamed()
         || target instanceof PlayerEntity player && (player.isCreative() || player.isSpectator() || !shooter.shouldDamagePlayer(player))) return;
      String tag = "convergence_hit_" + target.getUuid();
      if (!projectile.addCommandTag(tag)) return; // A totem can trigger both death and damage callbacks.

      switch (type) {
         case "convergence:infinity_arrow" -> {
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.GLOWING, 80, 0));
            Convergence.hurt(shooter, target, 12.0F);
            Convergence.sparks(shooter, target, 10);
         }
         case "convergence:void_arrow" -> {
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.SLOWNESS, 80, 2));
            Vec3d toward = shooter.getEntityPos().subtract(target.getEntityPos());
            if (toward.lengthSquared() > 0.01) {
               target.addVelocity(toward.normalize().multiply(0.9).add(0, 0.15, 0));
               target.velocityDirty = true;
            }
         }
         case "convergence:starfire_arrow" -> {
            target.setOnFireFor(5.0F);
            for (LivingEntity nearby : shooter.getEntityWorld().getEntitiesByClass(LivingEntity.class,
               target.getBoundingBox().expand(3.0), e -> e instanceof HostileEntity
                  && e != target && e.squaredDistanceTo(target) <= 9.0
                  && Convergence.valid(shooter, e))) {
               if (Convergence.hurt(shooter, nearby, 8.0F)) nearby.setOnFireFor(3.0F);
            }
            Convergence.sparks(shooter, target, 12);
         }
      }
   }

   private static final class WardShield extends ShieldItem {
      WardShield(Item.Settings settings) { super(settings); }

      @Override public ActionResult use(World world, PlayerEntity user, Hand hand) {
         if (!user.isSneaking()) return super.use(world, user, hand);
         if (user instanceof ServerPlayerEntity player
            && Convergence.id(player.getStackInHand(hand)).equals("convergence:shield")
            && Convergence.ready(player, "shield_ward", 600)) {
            Convergence.effect(player, StatusEffects.RESISTANCE, 2, 200);
            Convergence.effect(player, StatusEffects.ABSORPTION, 3, 200);
            for (LivingEntity hostile : Convergence.monsters(player, 6.0)) {
               Vec3d outward = hostile.getEntityPos().subtract(player.getEntityPos());
               if (outward.lengthSquared() > 0.01) {
                  hostile.addVelocity(outward.normalize().multiply(1.25).add(0, 0.4, 0));
                  hostile.velocityDirty = true;
               }
            }
            Convergence.say(player, "Infinity Ward: protected and repulsing hostiles");
         }
         return ActionResult.SUCCESS;
      }
   }
}
