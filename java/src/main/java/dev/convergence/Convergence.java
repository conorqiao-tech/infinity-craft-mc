package dev.convergence;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.util.Prediction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AllowDamage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.After;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission.HasCommandLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemAttributeModifiers.Builder;
import net.minecraft.world.item.component.ItemAttributeModifiers.Entry;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Tool.Rule;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

public class Convergence implements ModInitializer {
   public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
   public static final ResourceKey<DamageType> COMBO = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("convergence", "combo"));
   private static final Map<UUID, Convergence.State> STATES = new HashMap<>();
   static long clock;
   static final int ARM_TICKS = 200, FOLLOWUP_TICKS = 24;
   static final List<String> SWORD_MODES = List.of("Storm", "Blink", "Heal");

   static Convergence.State state(Player p) {
      return STATES.computeIfAbsent(p.getUUID(), k -> {
         State value = new State();
         value.dimension = p.level().dimension();
         value.peak = value.y = p.getY();
         value.age = p.tickCount;
         return value;
      });
   }

   static String id(ItemStack i) {
      return BuiltInRegistries.ITEM.getKey(i.getItem()).toString();
   }

   static boolean custom(ItemStack i) {
      return ITEMS.containsValue(i.getItem());
   }

   static boolean mace(ItemStack i) {
      return id(i).equals("convergence:mace");
   }

   static boolean spear(ItemStack i) {
      return id(i).equals("convergence:spear");
   }

   static boolean pair(Player p) {
      return mace(p.getMainHandItem()) && spear(p.getOffhandItem());
   }

   static boolean powered(Player p) {
      return !p.onGround() && !p.isInWater() && (state(p).drop >= 3.0 || p.getDeltaMovement().length() >= 0.8);
   }

   static void say(Player p, String s) {
      p.sendOverlayMessage(Component.literal(s));
   }

   static boolean ready(Player p, String k, int ticks) {
      if (p instanceof ServerPlayer player && Memberships.unlimitedGameplay(player)) return true;
      Convergence.State s = state(p);
      if (s.cooldown.getOrDefault(k, 0L) > clock) {
         return false;
      } else {
         s.cooldown.put(k, clock + (long)ticks);
         return true;
      }
   }

   static boolean coolingDown(ServerPlayer p,String key) {
      return !Memberships.unlimitedGameplay(p)&&state(p).cooldown.getOrDefault(key,0L)>clock;
   }

   static Item register(String name, Item base, int count, float attack, int durability, int protection) {
      Identifier id = Identifier.parse(name);
      Properties s = new Properties().setId(ResourceKey.create(Registries.ITEM, id));
      s.component(DataComponents.ITEM_NAME,GearNames.text(id.getPath()));

      BaseComponents.then(s, base, (b, defaults) -> {
         for (TypedDataComponent<?> c : defaults) {
            if (c.type() != DataComponents.ITEM_NAME && c.type() != DataComponents.ITEM_MODEL) {
               BaseComponents.copy(b, c);
            }
         }
      });

      if (durability > 0) {
         s.durability(durability);
      }

      s.stacksTo(count);
      if (count == 1) {
         s.component(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      }

      if (attack > 0.0F) {
         s.attributes(
            ItemAttributeModifiers.builder()
               .add(
                  Attributes.ATTACK_DAMAGE,
                  new AttributeModifier(Identifier.fromNamespaceAndPath("convergence", "attack"), (double)(attack - 1.0F), Operation.ADD_VALUE),
                  EquipmentSlotGroup.MAINHAND
               )
               .add(
                  Attributes.ATTACK_SPEED,
                  new AttributeModifier(Identifier.fromNamespaceAndPath("convergence", "speed"), -2.4, Operation.ADD_VALUE),
                  EquipmentSlotGroup.MAINHAND
               )
               .build()
         );
      }

      if (name.equals("convergence:sword")) {
         BaseComponents.then(s, Items.NETHERITE_PICKAXE, (b, pickaxe) -> {
            Tool tool = pickaxe.get(DataComponents.TOOL);
            b.set(
               DataComponents.TOOL,
               new Tool(tool.rules().stream().map(r -> new Rule(r.blocks(), Optional.of(999.0F), r.correctForDrops())).toList(), 999.0F, 0, true)
            );
         });
      }

      if (PoweredTools.IDS.contains(name)) {
         BaseComponents.then(s, base, (b, defaults) -> {
            Tool tool=defaults.get(DataComponents.TOOL);
            if(tool!=null)b.set(DataComponents.TOOL,new Tool(
               tool.rules().stream().map(r->new Rule(r.blocks(),r.speed().map(v->40.0F),r.correctForDrops())).toList(),tool.defaultMiningSpeed(),0,true));
         });
         s.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,true);
      }
      BaseComponents.then(s, base, (b, defaults) -> {
         Equippable eq = defaults.get(DataComponents.EQUIPPABLE);
         if (eq == null) return;
         ItemAttributeModifiers original = defaults.get(DataComponents.ATTRIBUTE_MODIFIERS);
         Builder armor = ItemAttributeModifiers.builder();

         for (Entry e : original.modifiers()) {
            armor.add(
               e.attribute(),
               e.attribute().equals(Attributes.ARMOR)
                  ? new AttributeModifier(e.modifier().id(), (double)protection, e.modifier().operation())
                  : e.modifier(),
               e.slot(),
               e.display()
            );
         }

         b.set(DataComponents.ATTRIBUTE_MODIFIERS, armor.build());
         ResourceKey<EquipmentAsset> model = ResourceKey.create(
            ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft", "equipment_asset")), Identifier.fromNamespaceAndPath(id.getNamespace(), "armor")
         );
         b.set(DataComponents.EQUIPPABLE, Equippable.builder(eq.slot()).setAsset(model).setDamageOnHurt(false).build());
      });

      if (name.equals("convergence:chestplate")) {
         s.component(DataComponents.GLIDER, Unit.INSTANCE);
      }

      Item item = (Item)Registry.register(BuiltInRegistries.ITEM, id, new Convergence.PowerItem(s));
      ITEMS.put(name, item);
      return item;
   }

   static int giveBuildingKit(ServerPlayer player) {
      if(!player.isAlive()||player.isSpectator()||!player.isCreative()&&!Memberships.gameplayBypass(player))return 0;
      for(String path:ExpandedGear.BLOCKS.stream().sorted().toList())
         player.getInventory().placeItemBackInInventory(new ItemStack(ITEMS.get("convergence:"+path),64), Prediction.SERVER_ONLY);
      for(String path:List.of("builder_wand","sculptor_wand"))player.getInventory().placeItemBackInInventory(ITEMS.get("convergence:"+path).getDefaultInstance(), Prediction.SERVER_ONLY);
      say(player,"Building kit: ten block styles and two Creative wands. Hold a block, then choose Swap Hands in Infinity Menu to use it in offhand.");return 1;
   }

   static int giveKit(ServerPlayer player) {
      if (!player.isAlive() || player.isSpectator()
          || (!player.isCreative() && !Memberships.gameplayBypass(player) && !player.createCommandSourceStack().permissions()
              .hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS)))) return 0;
      for (Item item : ITEMS.values()) {
         player.getInventory().placeItemBackInInventory(new ItemStack(item, item.getDefaultMaxStackSize() > 1 ? 64 : 1), Prediction.SERVER_ONLY);
      }
      player.getInventory().placeItemBackInInventory(new ItemStack(Items.FIREWORK_ROCKET, 64), Prediction.SERVER_ONLY);
      player.getInventory().placeItemBackInInventory(new ItemStack(Items.WHEAT_SEEDS, 64), Prediction.SERVER_ONLY);
      return 1;
   }

   static String helpText() {
      return "Select the Infinity Menu recovery compass for gear, powers, hand swapping, and AI helpers. "
         + "Sword: sneak+Use cycles Storm/Blink/Heal; Use casts. Mace with an offhand spear: Use arms the combo. "
         + "Mace: sneak+Use launches or dives. Spear: sneak+Use dashes. Tools: Use excavates, fells, digs, or farms; "
         + "sneak+Use pulls, cleaves, repels, or heals. Shield: blocks normally; sneak+Use casts Ward. "
         + "The held totem saves lethal damage. Bows fire Infinity, Void, and Starfire arrows. Radiant blocks glow. "
         + "Full armor enables all buffs, without Slow Falling. Admin can use gear and kits; other players need Creative or the relevant operator level. "
         + "AI Helpers lets OP4 owners create named golem companions; player targets and proposed server changes still need both approvals.";
   }

   /** Server-side hand placement avoids Bedrock's broken custom Creative-list drag. */
   static int holdCreativeItem(ServerPlayer player, String path) {
      if ((!player.isCreative() && !Memberships.gameplayBypass(player) && !player.createCommandSourceStack().permissions()
         .hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS))) || !player.isAlive() || player.isSpectator()) {
         player.sendSystemMessage(Component.literal("Infinity gear requires Creative, Admin or OP2."));
         return 0;
      }
      Item item = ITEMS.get("convergence:" + path);
      if (item == null) return 0;
      ItemStack previous = player.getMainHandItem().copy();
      if (!previous.isEmpty() && player.getInventory().getFreeSlot() < 0) {
         player.sendSystemMessage(Component.literal("Clear one inventory slot before replacing the item in your hand."));
         return 0;
      }
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, item.getDefaultMaxStackSize() > 1 ? 64 : 1));
      if (!previous.isEmpty()) player.getInventory().placeItemBackInInventory(previous, Prediction.SERVER_ONLY);
      player.containerMenu.broadcastChanges();
      player.sendSystemMessage(Component.literal("Holding " + item.getDefaultInstance().getHoverName().getString()
         + ". Select Infinity Menu to choose another."));
      return 1;
   }

   private static LiteralArgumentBuilder<CommandSourceStack> holdCommand() {
      var hold = Commands.literal("hold");
      for (String name : ITEMS.keySet()) {
         String path = name.substring("convergence:".length());
         hold.then(Commands.literal(path).executes(c -> holdCreativeItem(c.getSource().getPlayerOrException(), path)));
      }
      return hold;
   }

   public void onInitialize() {
      Catalog.register();
      ExpandedGear.register();
      OdysseyStructure.register();
      ExpandedGear.registerEvents();
      BackpackStorage.registerItems();
      CrossplaySupport.register();
      CommunityServer.register();
      Memberships.register();
      GameModes.register();
      CreativeGearPicker.register();
      LobbyServer.register();
      AchievementRewards.register();
      RewardTrades.register();
      BackpackStorage.register();
      PlayerTrading.register();
      AgentCompanions.initialize();
      ServerAssistant.initialize();
      ServerMenu.register();
      CreativeStudio.register();
      RcPlanes.register();
      GearCrates.register();
      OdysseyProphecy.register();
      AdminStatsMenu.register();
      AdminStats.setMenuOpener(AdminStatsMenu::open);
      AdminStats.register();
      Registry.register(
         BuiltInRegistries.CREATIVE_MODE_TAB,
         Identifier.fromNamespaceAndPath("convergence", "powers"),
         FabricCreativeModeTab.builder()
            .title(Component.literal("Infinity Armor"))
            .icon(() -> new ItemStack((ItemLike)ITEMS.get("convergence:mace")))
            .displayItems((ctx, e) -> ITEMS.values().forEach(e::accept))
            .build()
      );
      CommandRegistrationCallback.EVENT
         .register(
            (CommandRegistrationCallback)(dispatcher, access, environment) -> dispatcher.register(
                  (LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("convergence").then(Commands.literal("kit").executes(c -> giveKit(((CommandSourceStack)c.getSource()).getPlayerOrException()))
                     .then(Commands.literal("building").executes(c -> giveBuildingKit(c.getSource().getPlayerOrException())))))
                     .then(holdCommand())
                     .then(Commands.literal("gear").executes(c -> CreativeGearPicker.open(c.getSource().getPlayerOrException())))
                     .then(
                        Commands.literal("help")
                           .executes(
                              c -> {
                                 ((CommandSourceStack)c.getSource())
                                    .sendSuccess(
                                       () -> Component.literal(
                                             helpText()
                                          ),
                                       false
                                    );
                                 return 1;
                              }
                           )
                     )
               )
         );
      ServerTickEvents.START_SERVER_TICK.register(server -> clock++);
      ServerTickEvents.END_SERVER_TICK.register(Convergence::tick);
      AttackEntityCallback.EVENT.register((p, w, hand, entity, hit) -> {
         if (hand != InteractionHand.MAIN_HAND || !(p instanceof ServerPlayer sp)
             || !(entity instanceof LivingEntity target)) return InteractionResult.PASS;
         return melee(sp, target);
      });
      ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) ->
         !(e instanceof Player p && source.is(DamageTypeTags.IS_FALL)
           && id(p.getItemBySlot(EquipmentSlot.FEET)).equals("convergence:boots")));
      PlayerBlockBreakEvents.AFTER
         .register(
            (After)(world, p, pos, block, be) -> {
               if (!p.isCreative() && id(p.getMainHandItem()).equals("convergence:sword")) {
                  String b = BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath();
                  String drop = null;
                  if (b.equals("ancient_debris")) {
                     drop = "netherite_scrap";
                  } else if (b.equals("nether_gold_ore")) {
                     drop = "gold_ingot";
                  } else if (b.endsWith("_ore")) {
                     drop = b.replace("deepslate_", "").replace("nether_", "").replace("_ore", "");
                  }

                  if (Set.of("iron", "gold", "copper").contains(drop == null ? "" : drop)) {
                     drop = "raw_" + drop;
                  }

                  if ("lapis".equals(drop)) {
                     drop = "lapis_lazuli";
                  }

                  if (drop != null) {
                     Item i = (Item)BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(drop));
                     if (i != Items.AIR) {
                        world.addFreshEntity(
                           new ItemEntity(world, (double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, new ItemStack(i, 16))
                        );
                     }
                  }

                  if ((double)world.getRandom().nextFloat() < 0.5) {
                     Item[] extra = new Item[]{
                        Items.DIAMOND,
                        Items.EMERALD,
                        Items.GOLD_INGOT,
                        Items.IRON_INGOT,
                        Items.LAPIS_LAZULI,
                        Items.REDSTONE,
                        Items.NETHERITE_SCRAP,
                        Items.ANCIENT_DEBRIS,
                        ITEMS.get("convergence:ingot")
                     };
                     world.addFreshEntity(
                        new ItemEntity(
                           world,
                           (double)pos.getX() + 0.5,
                           (double)pos.getY() + 0.5,
                           (double)pos.getZ() + 0.5,
                           new ItemStack(extra[world.getRandom().nextInt(extra.length)], 1 + world.getRandom().nextInt(3))
                        )
                     );
                  }
               }
            }
         );
   }

   public static boolean valid(ServerPlayer p, LivingEntity e) {
      return e != p
         && p.isAlive()
         && e.isAlive()
         && !p.isSpectator()
         && p.level() == e.level()
         && !(e instanceof ArmorStand)
         && (!(e instanceof TamableAnimal t) || !t.isTame())
         && !e.entityTags().contains("convergence_friend")
         && (!(e instanceof Player q) || !q.isCreative() && !q.isSpectator() && p.canHarmPlayer(q));
   }

   static boolean visible(ServerPlayer p, LivingEntity e) {
      return p.level().clip(new ClipContext(p.getEyePosition(), e.getEyePosition(), Block.COLLIDER, Fluid.NONE, p)).getType() == Type.MISS;
   }

   static LivingEntity aim(ServerPlayer p, double range) {
      Vec3 start = p.getEyePosition();
      Vec3 end = start.add(p.getViewVector(1.0F).scale(range));
      double best = range * range;
      LivingEntity target = null;

      for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range), ex -> valid(p, ex))) {
         Optional<Vec3> point = e.getBoundingBox().inflate(0.25).clip(start, end);
         if (!point.isEmpty()) {
            double d = start.distanceToSqr(point.get());
            if (d < best && visible(p, e)) {
               best = d;
               target = e;
            }
         }
      }

      return target;
   }

   public static boolean hurt(ServerPlayer p, LivingEntity target, float amount) {
      if (!valid(p, target)) return false;
      return target.hurtServer(p.level(), new DamageSource(
         p.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(COMBO), p), amount);
   }

   static void feedback(ServerPlayer p, String key, String text) {
      State s = state(p);
      if (clock >= s.feedbackGlobal && (!key.equals(s.feedback) || clock >= s.feedbackUntil)) {
         s.feedback = key; s.feedbackUntil = clock + 10; s.feedbackGlobal = clock + 4;
         say(p, text);
      }
   }

   static void sparks(ServerPlayer p, LivingEntity target, int count) {
      p.level().sendParticles(ParticleTypes.ELECTRIC_SPARK,
         target.getX(), target.getY() + 1, target.getZ(), count, 0.25, 0.35, 0.25, 0.03);
   }

   static void cancelCombo(ServerPlayer p, String reason) {
      State s = state(p);
      boolean active = s.target != null || s.armed != 0;
      s.target = null; s.armed = 0;
      if (active) say(p, reason);
   }

   static boolean arm(ServerPlayer p) {
      State s = state(p);
      if (!pair(p)) return false;
      if (s.target != null) { feedback(p, "pending", "Mace follow-up already open"); return false; }
      if (coolingDown(p,"spear")) {
         feedback(p, "cooldown", "Spear recharging"); return false;
      }
      s.armed = clock + ARM_TICKS;
      say(p, "Combo armed for 10s — launch, dive and aim!");
      return true;
   }

   static boolean thrust(ServerPlayer p, LivingEntity target) {
      State s = state(p);
      if (s.target != null || coolingDown(p,"spear")
          || target == null || !valid(p, target) || p.distanceToSqr(target) > 36 || !visible(p, target)) return false;
      boolean charged = powered(p);
      if (!hurt(p, target, charged ? 1000 : 30)) {
         feedback(p, "rejected", s.armed > clock ? "Spear damage blocked — retrying while armed" : "Spear damage blocked — Use to try again");
         return false;
      }
      s.cooldown.put("spear", clock + 20);
      s.armed = 0;
      if (charged && valid(p, target)) {
         s.target = target.getUUID(); s.markDimension = p.level().dimension(); s.markUntil = clock + FOLLOWUP_TICKS; s.due = clock + 1;
         s.auto = pair(p); s.off = id(p.getOffhandItem());
         say(p, "Spear connected — close in for the mace!");
      } else say(p, charged ? "Spear connected" : "Spear thrust — dive to charge the combo");
      sparks(p, target, 12);
      return true;
   }

   static boolean finish(ServerPlayer p, LivingEntity target) {
      State s = state(p);
      if (s.target == null) return false;
      if (clock >= s.markUntil) { cancelCombo(p, "Mace window closed"); return false; }
      if (s.markDimension != p.level().dimension()) { cancelCombo(p, "Combo ended: dimension changed"); return false; }
      if (s.auto && !pair(p)) { cancelCombo(p, "Combo ended: keep mace + offhand spear"); return false; }
      if (target == null || !s.target.equals(target.getUUID())) {
         feedback(p, "target", "Mace follow-up needs the marked target"); return false;
      }
      if (!valid(p, target)) { cancelCombo(p, "Combo ended: target unavailable"); return false; }
      if (!mace(p.getMainHandItem())) return false;
      if (clock < s.due) { feedback(p, "wait", "Spear connected — mace ready next tick"); return false; }
      if (p.distanceToSqr(target) > 20.25) { feedback(p, "range", "Mace window open — move within 4.5 blocks"); return false; }
      if (!visible(p, target)) { feedback(p, "sight", "Mace blocked — lost line of sight"); return false; }
      if (!hurt(p, target, 3000)) { feedback(p, "blocked", "Mace damage blocked — window still open"); return false; }
      s.recentTarget = target.getUUID(); s.recentUntil = clock + 20;
      s.target = null;
      say(p, "INFINITY: Spear → Mace!");
      sparks(p, target, 24);
      return true;
   }

   // Exactly one path resolves each swing: a special scripted hit OR vanilla melee.
   static InteractionResult melee(ServerPlayer p, LivingEntity target) {
      if (!valid(p, target)) return InteractionResult.PASS;
      State s = state(p);
      if (mace(p.getMainHandItem())) {
         if (s.target != null) {
            boolean tooEarly = clock < s.due && s.target.equals(target.getUUID());
            if (finish(p, target) || tooEarly) {
               p.resetOnlyAttackStrengthTicker();
               return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
         }
         if (target.getUUID().equals(s.recentTarget) && clock < s.recentUntil) return InteractionResult.PASS;
         if (powered(p) && !coolingDown(p,"melee_smash")
             && hurt(p, target, (float)(120 + Math.min(300, s.drop * 12)))) {
            s.cooldown.put("melee_smash", clock + 20);
            p.resetOnlyAttackStrengthTicker();
            sparks(p, target, 12);
            return InteractionResult.SUCCESS;
         }
      } else if (spear(p.getMainHandItem()) && powered(p) && thrust(p, target)) {
         p.resetOnlyAttackStrengthTicker();
         return InteractionResult.SUCCESS;
      }
      return InteractionResult.PASS;
   }

   static void updateCombo(ServerPlayer p) {
      State s = state(p);
      if (s.armed != 0) {
         if (clock >= s.armed) cancelCombo(p, "Combo arm time expired — Use to rearm");
         else if (!pair(p)) cancelCombo(p, "Combo ended: equipment changed");
         else if (powered(p)) {
            if (clock % 4 == 0) sparks(p, p, 2);
            LivingEntity target = aim(p, 6);
            if (target != null) thrust(p, target);
            else feedback(p, "charged", "CHARGED — aim at a target within 6 blocks");
         } else feedback(p, "charging", "Combo armed — dive to charge");
      }
      if (s.target == null) return;
      if (clock >= s.markUntil) { cancelCombo(p, "Mace window closed"); return; }
      if (s.auto && (!pair(p) || !id(p.getOffhandItem()).equals(s.off))) {
         cancelCombo(p, "Combo ended: keep mace + offhand spear"); return;
      }
      if (!(p.level().getEntity(s.target) instanceof LivingEntity target) || !valid(p, target)) {
         cancelCombo(p, "Combo ended: target unavailable"); return;
      }
      if (clock % 4 == 0) {
         sparks(p, target, 2);
         if (p.distanceToSqr(target) <= 20.25) sparks(p, p, 2);
      }
      if (s.auto && clock >= s.due) finish(p, target);
   }

   static void tick(MinecraftServer server) {
      Set<UUID> online = new HashSet<>();

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         online.add(p.getUUID());
         if (!p.isAlive()) {
            STATES.remove(p.getUUID());
         } else {
            Convergence.State s = state(p);
            if (s.dimension != p.level().dimension() || p.tickCount < s.age || Math.abs(p.getY() - s.y) > 32.0) {
               s.peak = p.getY();
               s.armed = 0L;
               s.target = null;
            }

            s.dimension = p.level().dimension();
            s.age = p.tickCount;
            s.y = p.getY();
            if (p.onGround() || p.isInWater() || p.onClimbable()) {
               s.peak = p.getY();
            }

            s.peak = Math.max(s.peak, p.getY());
            s.drop = s.peak - p.getY();
            updateCombo(p);

            if (clock % 10L == 0L) {
               armor(p);
            }
         }
      }

      STATES.keySet().retainAll(online);
   }

   static void effect(Player p, Holder<MobEffect> e, int a, int d) {
      MobEffectInstance old = p.getEffect(e);
      if (old == null || old.getAmplifier() < a || old.getAmplifier() == a && old.getDuration() <= Math.max(20, d - 40)) {
         p.addEffect(new MobEffectInstance(e, d, a, true, false));
      }
   }

   static void armor(ServerPlayer p) {
      boolean head = id(p.getItemBySlot(EquipmentSlot.HEAD)).equals("convergence:helmet");
      boolean chest = id(p.getItemBySlot(EquipmentSlot.CHEST)).equals("convergence:chestplate");
      boolean legs = id(p.getItemBySlot(EquipmentSlot.LEGS)).equals("convergence:leggings");
      boolean feet = id(p.getItemBySlot(EquipmentSlot.FEET)).equals("convergence:boots");
      if (head) {
         effect(p, MobEffects.NIGHT_VISION, 0, 300);
         effect(p, MobEffects.WATER_BREATHING, 0, 80);
      }

      if (chest) {
         effect(p, MobEffects.FIRE_RESISTANCE, 0, 80);
         effect(p, MobEffects.REGENERATION, 2, 80);
         effect(p, MobEffects.RESISTANCE, 2, 80);
      }

      if (legs) {
         effect(p, MobEffects.SPEED, 2, 80);
         effect(p, MobEffects.STRENGTH, 4, 80);
      }

      if (feet) {
         effect(p, MobEffects.JUMP_BOOST, 2, 80);
      }

      if (head && chest && legs && feet) {
         effect(p, MobEffects.SPEED, 3, 100);
         effect(p, MobEffects.HASTE, 4, 100);
         effect(p, MobEffects.JUMP_BOOST, 3, 100);
         effect(p, MobEffects.REGENERATION, 4, 100);
         effect(p, MobEffects.RESISTANCE, 3, 100);
         effect(p, MobEffects.STRENGTH, 4, 100);
         effect(p, MobEffects.NIGHT_VISION, 0, 300);
         effect(p, MobEffects.WATER_BREATHING, 0, 100);
         effect(p, MobEffects.FIRE_RESISTANCE, 0, 100);
         effect(p, MobEffects.SATURATION, 1, 100);
         effect(p, MobEffects.HEALTH_BOOST, 9, 100);
         effect(p, MobEffects.ABSORPTION, 3, 100);
      }
   }

   static void push(ServerPlayer p, Vec3 v) {
      p.push(v);
      p.needsSync = true;
   }

   static void launch(ServerPlayer p) {
      if (ready(p, "launch", 30)) {
         Vec3 v = p.getViewVector(1.0F);
         push(p, new Vec3(v.x * 0.8, 1.7, v.z * 0.8));
         say(p, "Launch! Press Jump in the air to glide with winged armor.");
      }
   }

   static List<LivingEntity> monsters(ServerPlayer p, double r) {
      return p.level()
         .getEntitiesOfClass(
            LivingEntity.class,
            p.getBoundingBox().inflate(r),
            e -> e instanceof Monster && p.distanceToSqr(e) <= r * r && valid(p, e) && visible(p, e)
         )
         .stream()
         .limit(64L)
         .toList();
   }

   static void burst(ServerPlayer p, double r, float n) {
      int count = 0;

      for (LivingEntity e : monsters(p, r)) {
         if (hurt(p, e, n)) {
            count++;
         }
      }

      say(p, "Shockwave: " + count + " monsters");
   }

   static void swordPower(ServerPlayer p) {
      Convergence.State s = state(p);
      if (p.isShiftKeyDown()) {
         if (ready(p, "sword_mode", 10)) {
            s.swordMode = (s.swordMode + 1) % 3;
            say(p, "Sword: " + SWORD_MODES.get(s.swordMode) + " — release crouch, then Use");
         }
      } else {
         switch (s.swordMode) {
            case 0:
               if (!ready(p, "storm", 40)) {
                  return;
               }

               LivingEntity e = aim(p, 48.0);
               Set<UUID> struck = new HashSet<>();
               if (e != null) {
                  struck.add(e.getUUID()); hurt(p, e, 180.0F);
                  monsters(p, 48.0).stream().filter(t -> t != e && t.distanceToSqr(e) < 64.0).limit(7L)
                     .forEach(t -> { struck.add(t.getUUID()); hurt(p, t, 100.0F); });
               }

               for (LivingEntity t : monsters(p, 6.0)) {
                  if (struck.add(t.getUUID())) {
                     hurt(p, t, 40.0F);
                  }
               }

               say(p, "STORM: Chain lightning + shockwave");
               break;
            case 1:
               if (!ready(p, "blink", 40)) {
                  return;
               }

               Vec3 dest = null;
               Vec3 origin = p.position();
               Vec3 v = p.getViewVector(1.0F);

               for (double n = 0.5; n <= 24.0; n += 0.5) {
                  Vec3 q = origin.add(v.scale(n));
                  if (!p.level().noCollision(p, p.getBoundingBox().move(q.subtract(origin)))) {
                     break;
                  }

                  if (n >= 2.0) {
                     dest = q;
                  }
               }

               if (dest != null) {
                  p.randomTeleport(dest.x, dest.y, dest.z, false, state -> false);
                  p.fallDistance = 0;
                  p.setDeltaMovement(Vec3.ZERO);
                  p.needsSync = true;
                  s.peak = s.y = dest.y;
                  s.drop = 0;
                  s.target = null;
                  s.armed = 0L;
                  say(p, "BLINK");
               } else {
                  say(p, "Blink blocked");
               }
               break;
            case 2:
               if (!ready(p, "heal", 120)) {
                  return;
               }

               p.setHealth(p.getMaxHealth());
               p.clearFire();
               effect(p, MobEffects.REGENERATION, 4, 400);
               effect(p, MobEffects.ABSORPTION, 4, 6000);
               effect(p, MobEffects.SATURATION, 4, 200);
               effect(p, MobEffects.RESISTANCE, 2, 1200);
               say(p, "HEAL: Ambrosia restoration");
         }
      }
   }

   static void use(ServerPlayer p, String id) {
      if (p.isAlive() && !p.isSpectator()) {
         if (PoweredTools.IDS.contains(id)) { PoweredTools.aimed(p,id); return; }
         if (id.equals("convergence:sword")) {
            swordPower(p);
         } else {
            if (id.equals("convergence:mace")) {
               if (p.isShiftKeyDown()) {
                  if (p.onGround() || !(p.getViewVector(1.0F).y < -0.5)) {
                     launch(p);
                  } else if (ready(p, "dive", 30)) {
                     push(p, new Vec3(0.0, -2.0, 0.0));
                  }
               } else if (state(p).target != null) {
                  finish(p, aim(p, 4.5));
               } else if (pair(p)) {
                  arm(p);
               } else if (ready(p, "nova", 60)) {
                  burst(p, 40.0, (float)(1000.0 + Math.min(300.0, state(p).drop * 12.0)));
               }
            } else if (id.equals("convergence:spear")) {
               if (p.isShiftKeyDown()) {
                  if (ready(p, "dash", 20)) {
                     push(p, p.getViewVector(1.0F).scale(1.6).add(0.0, 0.15, 0.0));
                  }
               } else {
                  thrust(p, aim(p, 6.0));
               }
            }
         }
      }
   }

   static class PowerItem extends Item {
      PowerItem(Properties s) {
         super(s);
      }

      @Override
      public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
         String id=Convergence.id(context.getItemInHand());
         if (!PoweredTools.IDS.contains(id)) return super.useOn(context);
         if (context.getHand()!=InteractionHand.MAIN_HAND) return InteractionResult.PASS;
         if(context.getPlayer() instanceof ServerPlayer p) PoweredTools.use(p,id,context.getClickedPos(),context.getClickedFace());
         return InteractionResult.SUCCESS;
      }

      public InteractionResult use(Level world, Player user, InteractionHand hand) {
         String name = Convergence.id(user.getItemInHand(hand));
         if (!Set.of("convergence:sword", "convergence:mace", "convergence:spear").contains(name) && !PoweredTools.IDS.contains(name)) {
            return super.use(world, user, hand);
         } else {
            if (user instanceof ServerPlayer p) {
               Convergence.use(p, name);
            }

            return InteractionResult.SUCCESS;
         }
      }
   }

   static class State {
      double peak;
      double y;
      double drop;
      ResourceKey<Level> dimension;
      int age;
      int swordMode;
      long armed;
      long markUntil;
      long due;
      UUID target;
      UUID recentTarget;
      long recentUntil;
      boolean auto;
      String off;
      ResourceKey<Level> markDimension;
      String feedback = "";
      long feedbackUntil;
      long feedbackGlobal;
      Map<String, Long> cooldown = new HashMap<>();
   }
}
