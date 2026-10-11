package dev.convergence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;

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

   private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("convergence", path); }

   private static Item.Properties settings(String path, Item base, int maxCount, boolean unbreakable) {
      Item.Properties out = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(path)));
      out.component(DataComponents.ITEM_NAME,GearNames.text(path));
      BaseComponents.then(out, base, (builder, defaults) -> {
         for (TypedDataComponent<?> component : defaults) {
            if (component.type() != DataComponents.ITEM_NAME && component.type() != DataComponents.ITEM_MODEL) {
               BaseComponents.copy(builder, component);
            }
         }
      });
      out.stacksTo(maxCount);
      if (unbreakable) {
         out.durability(32767);
         out.component(DataComponents.UNBREAKABLE, Unit.INSTANCE);
         out.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
      return out;
   }

   private static void registerItem(String path, Item item) {
      Registry.register(BuiltInRegistries.ITEM, id(path), item);
      Convergence.ITEMS.put("convergence:" + path, item);
   }

   private static void registerBlock(String path, boolean radiant) {
      Identifier key = id(path);
      BlockBehaviour.Properties settings = BlockBehaviour.Properties.of()
         .setId(ResourceKey.create(Registries.BLOCK, key))
         .strength(hardness(path), NEW_BLOCKS.contains(path)?6.0F:1200.0F)
         .sound(NEW_BLOCKS.contains(path)?SoundType.STONE:SoundType.METAL)
         .requiresCorrectToolForDrops()
         .overrideLootTable(Optional.of(ResourceKey.create(Registries.LOOT_TABLE, id("blocks/" + path))));
      if (light(path)>0) settings.lightLevel(state -> light(path));
      Block block = Registry.register(BuiltInRegistries.BLOCK, key, new Block(settings));
      registerItem(path, new BlockItem(block, new Item.Properties()
         .setId(ResourceKey.create(Registries.ITEM, key))
         .component(DataComponents.ITEM_NAME,GearNames.text(path))
         .useBlockDescriptionPrefix()
         .stacksTo(64)));
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
      Item.Properties totem = settings("totem", Items.TOTEM_OF_UNDYING, 1, false);
      // Native death protection handles hand order, kill/void bypass, consumption,
      // statistics, criteria, and the totem animation. These effects are unique.
      totem.component(DataComponents.DEATH_PROTECTION, new DeathProtection(List.of(
         ClearAllStatusEffectsConsumeEffect.INSTANCE,
         new ApplyStatusEffectsConsumeEffect(List.of(
            new MobEffectInstance(MobEffects.REGENERATION, 800, 2),
            new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0),
            new MobEffectInstance(MobEffects.ABSORPTION, 200, 2),
            new MobEffectInstance(MobEffects.RESISTANCE, 40, 1)
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
      ResourceKey<EquipmentAsset> asset=ResourceKey.create(ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft","equipment_asset")),id(outfit+"_armor"));
      var config=BaseComponents.then(settings(path,base,1,false).attributes(ItemAttributeModifiers.builder().build()),base,(builder,defaults)->
            builder.set(DataComponents.EQUIPPABLE,Equippable.builder(defaults.get(DataComponents.EQUIPPABLE).slot()).setAsset(asset).setDamageOnHurt(false).build()))
         .component(DataComponents.DYED_COLOR,new DyedItemColor(outfit.equals("aurora")?0x48DCC8:0xF07A32))
         .component(DataComponents.UNBREAKABLE,Unit.INSTANCE);
      registerItem(path,new Item(config));
   }

   private static final class CreativeWand extends Item {
      CreativeWand(Item.Properties settings){super(settings);}
      @Override public InteractionResult useOn(UseOnContext context) {
         if(context.getHand()!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
         if(context.getPlayer() instanceof ServerPlayer p)
            PoweredTools.creativeUse(p,Convergence.id(context.getItemInHand()),context.getClickedPos(),context.getClickedFace());
         return InteractionResult.SUCCESS;
      }
      @Override public InteractionResult use(Level world,Player user,InteractionHand hand) {
         if(hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
         if(user instanceof ServerPlayer p)PoweredTools.creativeAimed(p,Convergence.id(user.getMainHandItem()));
         return InteractionResult.SUCCESS;
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
      if (!(source.getDirectEntity() instanceof AbstractArrow projectile)
         || !(source.getEntity() instanceof ServerPlayer shooter)) return;
      String type = Convergence.id(projectile.getPickupItemStackOrigin());
      if (!type.startsWith("convergence:") || !ARROWS.contains(type.substring("convergence:".length()))) return;
      // This also runs on fatal hits, when LivingEntity.isAlive() is already false.
      if (target == shooter || target.level() != shooter.level()
         || target instanceof ArmorStand || target.entityTags().contains("convergence_friend")
         || target instanceof TamableAnimal tameable && tameable.isTame()
         || target instanceof Player player && (player.isCreative() || player.isSpectator() || !shooter.canHarmPlayer(player))) return;
      String tag = "convergence_hit_" + target.getUUID();
      if (!projectile.addTag(tag)) return; // A totem can trigger both death and damage callbacks.

      switch (type) {
         case "convergence:infinity_arrow" -> {
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.GLOWING, 80, 0));
            Convergence.hurt(shooter, target, 12.0F);
            Convergence.sparks(shooter, target, 10);
         }
         case "convergence:void_arrow" -> {
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.SLOWNESS, 80, 2));
            Vec3 toward = shooter.position().subtract(target.position());
            if (toward.lengthSqr() > 0.01) {
               target.push(toward.normalize().scale(0.9).add(0, 0.15, 0));
               target.needsSync = true;
            }
         }
         case "convergence:starfire_arrow" -> {
            target.igniteForSeconds(5.0F);
            for (LivingEntity nearby : shooter.level().getEntitiesOfClass(LivingEntity.class,
               target.getBoundingBox().inflate(3.0), e -> e instanceof Monster
                  && e != target && e.distanceToSqr(target) <= 9.0
                  && Convergence.valid(shooter, e))) {
               if (Convergence.hurt(shooter, nearby, 8.0F)) nearby.igniteForSeconds(3.0F);
            }
            Convergence.sparks(shooter, target, 12);
         }
      }
   }

   private static final class WardShield extends ShieldItem {
      WardShield(Item.Properties settings) { super(settings); }

      @Override public InteractionResult use(Level world, Player user, InteractionHand hand) {
         if (!user.isShiftKeyDown()) return super.use(world, user, hand);
         if (user instanceof ServerPlayer player
            && Convergence.id(player.getItemInHand(hand)).equals("convergence:shield")
            && Convergence.ready(player, "shield_ward", 600)) {
            Convergence.effect(player, MobEffects.RESISTANCE, 2, 200);
            Convergence.effect(player, MobEffects.ABSORPTION, 3, 200);
            for (LivingEntity hostile : Convergence.monsters(player, 6.0)) {
               Vec3 outward = hostile.position().subtract(player.position());
               if (outward.lengthSqr() > 0.01) {
                  hostile.push(outward.normalize().scale(1.25).add(0, 0.4, 0));
                  hostile.needsSync = true;
               }
            }
            Convergence.say(player, "Infinity Ward: protected and repulsing hostiles");
         }
         return InteractionResult.SUCCESS;
      }
   }
}
