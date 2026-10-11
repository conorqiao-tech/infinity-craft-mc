package dev.convergence;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.Permission.Level;
import net.minecraft.component.Component;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.component.type.AttributeModifiersComponent.Builder;
import net.minecraft.component.type.AttributeModifiersComponent.Entry;
import net.minecraft.component.type.ToolComponent.Rule;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttributeModifier.Operation;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.Item.Settings;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.util.hit.HitResult.Type;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.RaycastContext.FluidHandling;
import net.minecraft.world.RaycastContext.ShapeType;

public class Convergence implements ModInitializer {
   public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
   public static final RegistryKey<DamageType> COMBO = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of("convergence", "combo"));
   private static final Map<UUID, Convergence.State> STATES = new HashMap<>();
   static long clock;
   static final int ARM_TICKS = 200, FOLLOWUP_TICKS = 24;
   static final List<String> SWORD_MODES = List.of("Storm", "Blink", "Heal");

   static Convergence.State state(PlayerEntity p) {
      return STATES.computeIfAbsent(p.getUuid(), k -> {
         State value = new State();
         value.dimension = p.getEntityWorld().getRegistryKey();
         value.peak = value.y = p.getY();
         value.age = p.age;
         return value;
      });
   }

   static String id(ItemStack i) {
      return Registries.ITEM.getId(i.getItem()).toString();
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

   static boolean pair(PlayerEntity p) {
      return mace(p.getMainHandStack()) && spear(p.getOffHandStack());
   }

   static boolean powered(PlayerEntity p) {
      return !p.isOnGround() && !p.isTouchingWater() && (state(p).drop >= 3.0 || p.getVelocity().length() >= 0.8);
   }

   static void say(PlayerEntity p, String s) {
      p.sendMessage(Text.literal(s), true);
   }

   static boolean ready(PlayerEntity p, String k, int ticks) {
      if (p instanceof ServerPlayerEntity player && Memberships.unlimitedGameplay(player)) return true;
      Convergence.State s = state(p);
      if (s.cooldown.getOrDefault(k, 0L) > clock) {
         return false;
      } else {
         s.cooldown.put(k, clock + (long)ticks);
         return true;
      }
   }

   static boolean coolingDown(ServerPlayerEntity p,String key) {
      return !Memberships.unlimitedGameplay(p)&&state(p).cooldown.getOrDefault(key,0L)>clock;
   }

   static <T> void copy(Settings s, Component<T> c) {
      s.component(c.type(), c.value());
   }

   static Item register(String name, Item base, int count, float attack, int durability, int protection) {
      Identifier id = Identifier.of(name);
      Settings s = new Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, id));
      s.component(DataComponentTypes.ITEM_NAME,GearNames.text(id.getPath()));

      for (Component<?> c : base.getComponents()) {
         if (c.type() != DataComponentTypes.ITEM_NAME && c.type() != DataComponentTypes.ITEM_MODEL) {
            copy(s, c);
         }
      }

      if (durability > 0) {
         s.maxDamage(durability);
      }

      s.maxCount(count);
      if (count == 1) {
         s.component(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
      }

      if (attack > 0.0F) {
         s.attributeModifiers(
            AttributeModifiersComponent.builder()
               .add(
                  EntityAttributes.ATTACK_DAMAGE,
                  new EntityAttributeModifier(Identifier.of("convergence", "attack"), (double)(attack - 1.0F), Operation.ADD_VALUE),
                  AttributeModifierSlot.MAINHAND
               )
               .add(
                  EntityAttributes.ATTACK_SPEED,
                  new EntityAttributeModifier(Identifier.of("convergence", "speed"), -2.4, Operation.ADD_VALUE),
                  AttributeModifierSlot.MAINHAND
               )
               .build()
         );
      }

      EquippableComponent eq = (EquippableComponent)base.getComponents().get(DataComponentTypes.EQUIPPABLE);
      if (name.equals("convergence:sword")) {
         ToolComponent tool = (ToolComponent)Items.NETHERITE_PICKAXE.getComponents().get(DataComponentTypes.TOOL);
         s.component(
            DataComponentTypes.TOOL,
            new ToolComponent(tool.rules().stream().map(r -> new Rule(r.blocks(), Optional.of(999.0F), r.correctForDrops())).toList(), 999.0F, 0, true)
         );
      }

      if (PoweredTools.IDS.contains(name)) {
         ToolComponent tool=base.getComponents().get(DataComponentTypes.TOOL);
         if(tool!=null)s.component(DataComponentTypes.TOOL,new ToolComponent(
            tool.rules().stream().map(r->new Rule(r.blocks(),r.speed().map(v->40.0F),r.correctForDrops())).toList(),tool.defaultMiningSpeed(),0,true));
         s.component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,true);
      }
      if (eq != null) {
         AttributeModifiersComponent original = (AttributeModifiersComponent)base.getComponents().get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
         Builder armor = AttributeModifiersComponent.builder();

         for (Entry e : original.modifiers()) {
            armor.add(
               e.attribute(),
               e.attribute().equals(EntityAttributes.ARMOR)
                  ? new EntityAttributeModifier(e.modifier().id(), (double)protection, e.modifier().operation())
                  : e.modifier(),
               e.slot(),
               e.display()
            );
         }

         s.attributeModifiers(armor.build());
         RegistryKey<EquipmentAsset> model = RegistryKey.of(
            RegistryKey.ofRegistry(Identifier.of("minecraft", "equipment_asset")), Identifier.of(id.getNamespace(), "armor")
         );
         s.component(DataComponentTypes.EQUIPPABLE, EquippableComponent.builder(eq.slot()).model(model).damageOnHurt(false).build());
      }

      if (name.equals("convergence:chestplate")) {
         s.component(DataComponentTypes.GLIDER, Unit.INSTANCE);
      }

      Item item = (Item)Registry.register(Registries.ITEM, id, new Convergence.PowerItem(s));
      ITEMS.put(name, item);
      return item;
   }

   static int giveBuildingKit(ServerPlayerEntity player) {
      if(!player.isAlive()||player.isSpectator()||!player.isCreative()&&!Memberships.gameplayBypass(player))return 0;
      for(String path:ExpandedGear.BLOCKS.stream().sorted().toList())
         player.getInventory().offerOrDrop(new ItemStack(ITEMS.get("convergence:"+path),64));
      for(String path:List.of("builder_wand","sculptor_wand"))player.getInventory().offerOrDrop(ITEMS.get("convergence:"+path).getDefaultStack());
      say(player,"Building kit: ten block styles and two Creative wands. Hold a block, then choose Swap Hands in Infinity Menu to use it in offhand.");return 1;
   }

   static int giveKit(ServerPlayerEntity player) {
      if (!player.isAlive() || player.isSpectator()
          || (!player.isCreative() && !Memberships.gameplayBypass(player) && !player.getCommandSource().getPermissions()
              .hasPermission(new Level(PermissionLevel.GAMEMASTERS)))) return 0;
      for (Item item : ITEMS.values()) {
         player.getInventory().offerOrDrop(new ItemStack(item, item.getMaxCount() > 1 ? 64 : 1));
      }
      player.getInventory().offerOrDrop(new ItemStack(Items.FIREWORK_ROCKET, 64));
      player.getInventory().offerOrDrop(new ItemStack(Items.WHEAT_SEEDS, 64));
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
   static int holdCreativeItem(ServerPlayerEntity player, String path) {
      if ((!player.isCreative() && !Memberships.gameplayBypass(player) && !player.getCommandSource().getPermissions()
         .hasPermission(new Level(PermissionLevel.GAMEMASTERS))) || !player.isAlive() || player.isSpectator()) {
         player.sendMessage(Text.literal("Infinity gear requires Creative, Admin or OP2."), false);
         return 0;
      }
      Item item = ITEMS.get("convergence:" + path);
      if (item == null) return 0;
      ItemStack previous = player.getMainHandStack().copy();
      if (!previous.isEmpty() && player.getInventory().getEmptySlot() < 0) {
         player.sendMessage(Text.literal("Clear one inventory slot before replacing the item in your hand."), false);
         return 0;
      }
      player.setStackInHand(Hand.MAIN_HAND, new ItemStack(item, item.getMaxCount() > 1 ? 64 : 1));
      if (!previous.isEmpty()) player.getInventory().offerOrDrop(previous);
      player.currentScreenHandler.sendContentUpdates();
      player.sendMessage(Text.literal("Holding " + item.getDefaultStack().getName().getString()
         + ". Select Infinity Menu to choose another."), false);
      return 1;
   }

   private static LiteralArgumentBuilder<ServerCommandSource> holdCommand() {
      var hold = CommandManager.literal("hold");
      for (String name : ITEMS.keySet()) {
         String path = name.substring("convergence:".length());
         hold.then(CommandManager.literal(path).executes(c -> holdCreativeItem(c.getSource().getPlayerOrThrow(), path)));
      }
      return hold;
   }

   public void onInitialize() {
      Catalog.register();
      ExpandedGear.register();
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
      GearCrates.register();
      AdminStatsMenu.register();
      AdminStats.setMenuOpener(AdminStatsMenu::open);
      AdminStats.register();
      Registry.register(
         Registries.ITEM_GROUP,
         Identifier.of("convergence", "powers"),
         FabricItemGroup.builder()
            .displayName(Text.literal("Infinity Armor"))
            .icon(() -> new ItemStack((ItemConvertible)ITEMS.get("convergence:mace")))
            .entries((ctx, e) -> ITEMS.values().forEach(e::add))
            .build()
      );
      CommandRegistrationCallback.EVENT
         .register(
            (CommandRegistrationCallback)(dispatcher, access, environment) -> dispatcher.register(
                  (LiteralArgumentBuilder)((LiteralArgumentBuilder)CommandManager.literal("convergence").then(CommandManager.literal("kit").executes(c -> giveKit(((ServerCommandSource)c.getSource()).getPlayerOrThrow()))
                     .then(CommandManager.literal("building").executes(c -> giveBuildingKit(c.getSource().getPlayerOrThrow())))))
                     .then(holdCommand())
                     .then(CommandManager.literal("gear").executes(c -> CreativeGearPicker.open(c.getSource().getPlayerOrThrow())))
                     .then(
                        CommandManager.literal("help")
                           .executes(
                              c -> {
                                 ((ServerCommandSource)c.getSource())
                                    .sendFeedback(
                                       () -> Text.literal(
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
         if (hand != Hand.MAIN_HAND || !(p instanceof ServerPlayerEntity sp)
             || !(entity instanceof LivingEntity target)) return ActionResult.PASS;
         return melee(sp, target);
      });
      ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) ->
         !(e instanceof PlayerEntity p && source.isIn(DamageTypeTags.IS_FALL)
           && id(p.getEquippedStack(EquipmentSlot.FEET)).equals("convergence:boots")));
      PlayerBlockBreakEvents.AFTER
         .register(
            (After)(world, p, pos, block, be) -> {
               if (!p.isCreative() && id(p.getMainHandStack()).equals("convergence:sword")) {
                  String b = Registries.BLOCK.getId(block.getBlock()).getPath();
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
                     Item i = (Item)Registries.ITEM.get(Identifier.ofVanilla(drop));
                     if (i != Items.AIR) {
                        world.spawnEntity(
                           new ItemEntity(world, (double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, new ItemStack(i, 16))
                        );
                     }
                  }

                  if ((double)world.random.nextFloat() < 0.5) {
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
                     world.spawnEntity(
                        new ItemEntity(
                           world,
                           (double)pos.getX() + 0.5,
                           (double)pos.getY() + 0.5,
                           (double)pos.getZ() + 0.5,
                           new ItemStack(extra[world.random.nextInt(extra.length)], 1 + world.random.nextInt(3))
                        )
                     );
                  }
               }
            }
         );
   }

   public static boolean valid(ServerPlayerEntity p, LivingEntity e) {
      return e != p
         && p.isAlive()
         && e.isAlive()
         && !p.isSpectator()
         && p.getEntityWorld() == e.getEntityWorld()
         && !(e instanceof ArmorStandEntity)
         && (!(e instanceof TameableEntity t) || !t.isTamed())
         && !e.getCommandTags().contains("convergence_friend")
         && (!(e instanceof PlayerEntity q) || !q.isCreative() && !q.isSpectator() && p.shouldDamagePlayer(q));
   }

   static boolean visible(ServerPlayerEntity p, LivingEntity e) {
      return p.getEntityWorld().raycast(new RaycastContext(p.getEyePos(), e.getEyePos(), ShapeType.COLLIDER, FluidHandling.NONE, p)).getType() == Type.MISS;
   }

   static LivingEntity aim(ServerPlayerEntity p, double range) {
      Vec3d start = p.getEyePos();
      Vec3d end = start.add(p.getRotationVec(1.0F).multiply(range));
      double best = range * range;
      LivingEntity target = null;

      for (LivingEntity e : p.getEntityWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(range), ex -> valid(p, ex))) {
         Optional<Vec3d> point = e.getBoundingBox().expand(0.25).raycast(start, end);
         if (!point.isEmpty()) {
            double d = start.squaredDistanceTo(point.get());
            if (d < best && visible(p, e)) {
               best = d;
               target = e;
            }
         }
      }

      return target;
   }

   public static boolean hurt(ServerPlayerEntity p, LivingEntity target, float amount) {
      if (!valid(p, target)) return false;
      return target.damage(p.getEntityWorld(), new DamageSource(
         p.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE).getOrThrow(COMBO), p), amount);
   }

   static void feedback(ServerPlayerEntity p, String key, String text) {
      State s = state(p);
      if (clock >= s.feedbackGlobal && (!key.equals(s.feedback) || clock >= s.feedbackUntil)) {
         s.feedback = key; s.feedbackUntil = clock + 10; s.feedbackGlobal = clock + 4;
         say(p, text);
      }
   }

   static void sparks(ServerPlayerEntity p, LivingEntity target, int count) {
      p.getEntityWorld().spawnParticles(ParticleTypes.ELECTRIC_SPARK,
         target.getX(), target.getY() + 1, target.getZ(), count, 0.25, 0.35, 0.25, 0.03);
   }

   static void cancelCombo(ServerPlayerEntity p, String reason) {
      State s = state(p);
      boolean active = s.target != null || s.armed != 0;
      s.target = null; s.armed = 0;
      if (active) say(p, reason);
   }

   static boolean arm(ServerPlayerEntity p) {
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

   static boolean thrust(ServerPlayerEntity p, LivingEntity target) {
      State s = state(p);
      if (s.target != null || coolingDown(p,"spear")
          || target == null || !valid(p, target) || p.squaredDistanceTo(target) > 36 || !visible(p, target)) return false;
      boolean charged = powered(p);
      if (!hurt(p, target, charged ? 1000 : 30)) {
         feedback(p, "rejected", s.armed > clock ? "Spear damage blocked — retrying while armed" : "Spear damage blocked — Use to try again");
         return false;
      }
      s.cooldown.put("spear", clock + 20);
      s.armed = 0;
      if (charged && valid(p, target)) {
         s.target = target.getUuid(); s.markDimension = p.getEntityWorld().getRegistryKey(); s.markUntil = clock + FOLLOWUP_TICKS; s.due = clock + 1;
         s.auto = pair(p); s.off = id(p.getOffHandStack());
         say(p, "Spear connected — close in for the mace!");
      } else say(p, charged ? "Spear connected" : "Spear thrust — dive to charge the combo");
      sparks(p, target, 12);
      return true;
   }

   static boolean finish(ServerPlayerEntity p, LivingEntity target) {
      State s = state(p);
      if (s.target == null) return false;
      if (clock >= s.markUntil) { cancelCombo(p, "Mace window closed"); return false; }
      if (s.markDimension != p.getEntityWorld().getRegistryKey()) { cancelCombo(p, "Combo ended: dimension changed"); return false; }
      if (s.auto && !pair(p)) { cancelCombo(p, "Combo ended: keep mace + offhand spear"); return false; }
      if (target == null || !s.target.equals(target.getUuid())) {
         feedback(p, "target", "Mace follow-up needs the marked target"); return false;
      }
      if (!valid(p, target)) { cancelCombo(p, "Combo ended: target unavailable"); return false; }
      if (!mace(p.getMainHandStack())) return false;
      if (clock < s.due) { feedback(p, "wait", "Spear connected — mace ready next tick"); return false; }
      if (p.squaredDistanceTo(target) > 20.25) { feedback(p, "range", "Mace window open — move within 4.5 blocks"); return false; }
      if (!visible(p, target)) { feedback(p, "sight", "Mace blocked — lost line of sight"); return false; }
      if (!hurt(p, target, 3000)) { feedback(p, "blocked", "Mace damage blocked — window still open"); return false; }
      s.recentTarget = target.getUuid(); s.recentUntil = clock + 20;
      s.target = null;
      say(p, "INFINITY: Spear → Mace!");
      sparks(p, target, 24);
      return true;
   }

   // Exactly one path resolves each swing: a special scripted hit OR vanilla melee.
   static ActionResult melee(ServerPlayerEntity p, LivingEntity target) {
      if (!valid(p, target)) return ActionResult.PASS;
      State s = state(p);
      if (mace(p.getMainHandStack())) {
         if (s.target != null) {
            boolean tooEarly = clock < s.due && s.target.equals(target.getUuid());
            if (finish(p, target) || tooEarly) {
               p.resetTicksSinceLastAttack();
               return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
         }
         if (target.getUuid().equals(s.recentTarget) && clock < s.recentUntil) return ActionResult.PASS;
         if (powered(p) && !coolingDown(p,"melee_smash")
             && hurt(p, target, (float)(120 + Math.min(300, s.drop * 12)))) {
            s.cooldown.put("melee_smash", clock + 20);
            p.resetTicksSinceLastAttack();
            sparks(p, target, 12);
            return ActionResult.SUCCESS;
         }
      } else if (spear(p.getMainHandStack()) && powered(p) && thrust(p, target)) {
         p.resetTicksSinceLastAttack();
         return ActionResult.SUCCESS;
      }
      return ActionResult.PASS;
   }

   static void updateCombo(ServerPlayerEntity p) {
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
      if (s.auto && (!pair(p) || !id(p.getOffHandStack()).equals(s.off))) {
         cancelCombo(p, "Combo ended: keep mace + offhand spear"); return;
      }
      if (!(p.getEntityWorld().getEntity(s.target) instanceof LivingEntity target) || !valid(p, target)) {
         cancelCombo(p, "Combo ended: target unavailable"); return;
      }
      if (clock % 4 == 0) {
         sparks(p, target, 2);
         if (p.squaredDistanceTo(target) <= 20.25) sparks(p, p, 2);
      }
      if (s.auto && clock >= s.due) finish(p, target);
   }

   static void tick(MinecraftServer server) {
      Set<UUID> online = new HashSet<>();

      for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
         online.add(p.getUuid());
         if (!p.isAlive()) {
            STATES.remove(p.getUuid());
         } else {
            Convergence.State s = state(p);
            if (s.dimension != p.getEntityWorld().getRegistryKey() || p.age < s.age || Math.abs(p.getY() - s.y) > 32.0) {
               s.peak = p.getY();
               s.armed = 0L;
               s.target = null;
            }

            s.dimension = p.getEntityWorld().getRegistryKey();
            s.age = p.age;
            s.y = p.getY();
            if (p.isOnGround() || p.isTouchingWater() || p.isClimbing()) {
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

   static void effect(PlayerEntity p, RegistryEntry<StatusEffect> e, int a, int d) {
      StatusEffectInstance old = p.getStatusEffect(e);
      if (old == null || old.getAmplifier() < a || old.getAmplifier() == a && old.getDuration() <= Math.max(20, d - 40)) {
         p.addStatusEffect(new StatusEffectInstance(e, d, a, true, false));
      }
   }

   static void armor(ServerPlayerEntity p) {
      boolean head = id(p.getEquippedStack(EquipmentSlot.HEAD)).equals("convergence:helmet");
      boolean chest = id(p.getEquippedStack(EquipmentSlot.CHEST)).equals("convergence:chestplate");
      boolean legs = id(p.getEquippedStack(EquipmentSlot.LEGS)).equals("convergence:leggings");
      boolean feet = id(p.getEquippedStack(EquipmentSlot.FEET)).equals("convergence:boots");
      if (head) {
         effect(p, StatusEffects.NIGHT_VISION, 0, 300);
         effect(p, StatusEffects.WATER_BREATHING, 0, 80);
      }

      if (chest) {
         effect(p, StatusEffects.FIRE_RESISTANCE, 0, 80);
         effect(p, StatusEffects.REGENERATION, 2, 80);
         effect(p, StatusEffects.RESISTANCE, 2, 80);
      }

      if (legs) {
         effect(p, StatusEffects.SPEED, 2, 80);
         effect(p, StatusEffects.STRENGTH, 4, 80);
      }

      if (feet) {
         effect(p, StatusEffects.JUMP_BOOST, 2, 80);
      }

      if (head && chest && legs && feet) {
         effect(p, StatusEffects.SPEED, 3, 100);
         effect(p, StatusEffects.HASTE, 4, 100);
         effect(p, StatusEffects.JUMP_BOOST, 3, 100);
         effect(p, StatusEffects.REGENERATION, 4, 100);
         effect(p, StatusEffects.RESISTANCE, 3, 100);
         effect(p, StatusEffects.STRENGTH, 4, 100);
         effect(p, StatusEffects.NIGHT_VISION, 0, 300);
         effect(p, StatusEffects.WATER_BREATHING, 0, 100);
         effect(p, StatusEffects.FIRE_RESISTANCE, 0, 100);
         effect(p, StatusEffects.SATURATION, 1, 100);
         effect(p, StatusEffects.HEALTH_BOOST, 9, 100);
         effect(p, StatusEffects.ABSORPTION, 3, 100);
      }
   }

   static void push(ServerPlayerEntity p, Vec3d v) {
      p.addVelocity(v);
      p.velocityDirty = true;
   }

   static void launch(ServerPlayerEntity p) {
      if (ready(p, "launch", 30)) {
         Vec3d v = p.getRotationVec(1.0F);
         push(p, new Vec3d(v.x * 0.8, 1.7, v.z * 0.8));
         say(p, "Launch! Press Jump in the air to glide with winged armor.");
      }
   }

   static List<LivingEntity> monsters(ServerPlayerEntity p, double r) {
      return p.getEntityWorld()
         .getEntitiesByClass(
            LivingEntity.class,
            p.getBoundingBox().expand(r),
            e -> e instanceof HostileEntity && p.squaredDistanceTo(e) <= r * r && valid(p, e) && visible(p, e)
         )
         .stream()
         .limit(64L)
         .toList();
   }

   static void burst(ServerPlayerEntity p, double r, float n) {
      int count = 0;

      for (LivingEntity e : monsters(p, r)) {
         if (hurt(p, e, n)) {
            count++;
         }
      }

      say(p, "Shockwave: " + count + " monsters");
   }

   static void swordPower(ServerPlayerEntity p) {
      Convergence.State s = state(p);
      if (p.isSneaking()) {
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
                  struck.add(e.getUuid()); hurt(p, e, 180.0F);
                  monsters(p, 48.0).stream().filter(t -> t != e && t.squaredDistanceTo(e) < 64.0).limit(7L)
                     .forEach(t -> { struck.add(t.getUuid()); hurt(p, t, 100.0F); });
               }

               for (LivingEntity t : monsters(p, 6.0)) {
                  if (struck.add(t.getUuid())) {
                     hurt(p, t, 40.0F);
                  }
               }

               say(p, "STORM: Chain lightning + shockwave");
               break;
            case 1:
               if (!ready(p, "blink", 40)) {
                  return;
               }

               Vec3d dest = null;
               Vec3d origin = p.getEntityPos();
               Vec3d v = p.getRotationVec(1.0F);

               for (double n = 0.5; n <= 24.0; n += 0.5) {
                  Vec3d q = origin.add(v.multiply(n));
                  if (!p.getEntityWorld().isSpaceEmpty(p, p.getBoundingBox().offset(q.subtract(origin)))) {
                     break;
                  }

                  if (n >= 2.0) {
                     dest = q;
                  }
               }

               if (dest != null) {
                  p.teleport(dest.x, dest.y, dest.z, false);
                  p.fallDistance = 0;
                  p.setVelocity(Vec3d.ZERO);
                  p.velocityDirty = true;
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
               p.extinguish();
               effect(p, StatusEffects.REGENERATION, 4, 400);
               effect(p, StatusEffects.ABSORPTION, 4, 6000);
               effect(p, StatusEffects.SATURATION, 4, 200);
               effect(p, StatusEffects.RESISTANCE, 2, 1200);
               say(p, "HEAL: Ambrosia restoration");
         }
      }
   }

   static void use(ServerPlayerEntity p, String id) {
      if (p.isAlive() && !p.isSpectator()) {
         if (PoweredTools.IDS.contains(id)) { PoweredTools.aimed(p,id); return; }
         if (id.equals("convergence:sword")) {
            swordPower(p);
         } else {
            if (id.equals("convergence:mace")) {
               if (p.isSneaking()) {
                  if (p.isOnGround() || !(p.getRotationVec(1.0F).y < -0.5)) {
                     launch(p);
                  } else if (ready(p, "dive", 30)) {
                     push(p, new Vec3d(0.0, -2.0, 0.0));
                  }
               } else if (state(p).target != null) {
                  finish(p, aim(p, 4.5));
               } else if (pair(p)) {
                  arm(p);
               } else if (ready(p, "nova", 60)) {
                  burst(p, 40.0, (float)(1000.0 + Math.min(300.0, state(p).drop * 12.0)));
               }
            } else if (id.equals("convergence:spear")) {
               if (p.isSneaking()) {
                  if (ready(p, "dash", 20)) {
                     push(p, p.getRotationVec(1.0F).multiply(1.6).add(0.0, 0.15, 0.0));
                  }
               } else {
                  thrust(p, aim(p, 6.0));
               }
            }
         }
      }
   }

   static class PowerItem extends Item {
      PowerItem(Settings s) {
         super(s);
      }

      @Override
      public ActionResult useOnBlock(net.minecraft.item.ItemUsageContext context) {
         String id=Convergence.id(context.getStack());
         if (!PoweredTools.IDS.contains(id)) return super.useOnBlock(context);
         if (context.getHand()!=Hand.MAIN_HAND) return ActionResult.PASS;
         if(context.getPlayer() instanceof ServerPlayerEntity p) PoweredTools.use(p,id,context.getBlockPos(),context.getSide());
         return ActionResult.SUCCESS;
      }

      public ActionResult use(World world, PlayerEntity user, Hand hand) {
         String name = Convergence.id(user.getStackInHand(hand));
         if (!Set.of("convergence:sword", "convergence:mace", "convergence:spear").contains(name) && !PoweredTools.IDS.contains(name)) {
            return super.use(world, user, hand);
         } else {
            if (user instanceof ServerPlayerEntity p) {
               Convergence.use(p, name);
            }

            return ActionResult.SUCCESS;
         }
      }
   }

   static class State {
      double peak;
      double y;
      double drop;
      RegistryKey<World> dimension;
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
      RegistryKey<World> markDimension;
      String feedback = "";
      long feedbackUntil;
      long feedbackGlobal;
      Map<String, Long> cooldown = new HashMap<>();
   }
}
