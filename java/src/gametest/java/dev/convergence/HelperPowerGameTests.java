package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Native migration/creation boundaries; no stat changes are inferred from a menu label. */
public class HelperPowerGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper c;
        final AgentCompanions previous, helpers;
        final ServerPlayer owner;
        final Path directory;
        final Vec3 center;
        Fixture(GameTestHelper c, String name) throws Exception {
            this.c = c; var server = c.getLevel().getServer(); previous = AgentCompanions.get(server);
            c.assertTrue(previous.data.agents.isEmpty(), "Power fixture does not replace an unrelated squad");
            directory = Files.createTempDirectory("infinity-helper-power-");
            helpers = new AgentCompanions(server, directory.resolve("agents.json")); AgentCompanions.INSTANCES.put(server, helpers);
            owner = new ModeGameTests().player(c, name); OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SURVIVAL); owner.setNoGravity(true);
            var feet = c.absolutePos(new BlockPos(20, 55, 20));
            for (var at : BlockPos.betweenClosed(feet.offset(-17, -1, -17), feet.offset(17, 4, 17)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            center = Vec3.atBottomCenterOf(feet); owner.setPos(center); helpers.save();
        }
        IronGolem fresh(String name) {
            helpers.spawn(owner, name); var entry = helpers.owned(owner, name);
            if (entry == null) throw new IllegalStateException("Explicit native helper creation failed");
            return helpers.loaded.get(UUID.fromString(entry.getKey()));
        }
        IronGolem legacy(String name) {
            var helper = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
            helper.setPos(center.add(4, 0, 0)); helper.addTag(AgentCompanions.TAG);
            helpers.data.agents.put(helper.getStringUUID(), new AgentCompanions.Agent(owner.getStringUUID(), name,
                AgentCompanions.Mode.FOLLOW, AgentCompanions.Profile.REGULAR, c.getLevel().dimension().identifier().toString(), helper.getX(), helper.getY(), helper.getZ()));
            // Deliberately not inserted yet: first ENTITY_LOAD/prepare must perform the migration.
            return helper;
        }
        @Override public void close() throws Exception {
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            for (var entity : new ArrayList<>(helpers.loaded.values())) { AgentAvatars.remove(entity); entity.discard(); }
            helpers.loaded.clear(); helpers.data.agents.clear(); owner.closeContainer(); OperatorGameTests.deop(owner);
            c.getLevel().getServer().getPlayerList().remove(owner);
            if (AgentCompanions.INSTANCES.get(helpers.server) == helpers) AgentCompanions.INSTANCES.put(helpers.server, previous);
            try (var paths = Files.walk(directory)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static CompoundTag save(IronGolem helper) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.registryAccess()); helper.saveWithoutId(output); return output.buildResult();
    }
    private static void defaults(GameTestHelper c, IronGolem helper) {
        c.assertValueEqual(helper.getAttributeBaseValue(Attributes.MAX_HEALTH), 1000d, "Native helper health capacity is OP by default");
        c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 100d, "Native helper attack base is OP by default");
        c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ARMOR), 30d, "Native helper armor base is upgraded");
        c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ARMOR_TOUGHNESS), 20d, "Native helper toughness base is upgraded");
        c.assertValueEqual(helper.getAttributeBaseValue(Attributes.KNOCKBACK_RESISTANCE), 1d, "Native helper knockback resistance stays finite");
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void explicitSingleAndBatchCreationFillOnlyNewHelpersAndLeaveVanillaGolemsAlone(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "power-create")) {
            var ordinary = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
            var helper = f.fresh("old"); defaults(c, helper);
            c.assertValueEqual(helper.getHealth(), 1000f, "An explicitly new helper begins at its new native maximum");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), ordinary.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), "Default power never changes walking speed");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.SCALE), ordinary.getAttributeBaseValue(Attributes.SCALE), "Default power never changes size");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.GRAVITY), ordinary.getAttributeBaseValue(Attributes.GRAVITY), "Default power never changes gravity");
            helper.setHealth(37); var id = helper.getUUID();
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.ULTIMATE_FINALS), 2, "Normal native batch creation creates exactly two helpers");
            for (var created : f.helpers.loaded.values()) {
                defaults(c, created);
                c.assertValueEqual(created.getHealth(), created == helper ? 37f : 1000f, "A later batch fills only its newly created entities");
            }
            c.assertTrue(f.helpers.loaded.get(id) == helper, "Existing native identity is preserved");
            HelperPowerDefaults.applyRegistered(ordinary); ordinary.addTag(AgentCompanions.TAG); HelperPowerDefaults.applyRegistered(ordinary);
            c.assertValueEqual(ordinary.getMaxHealth(), 100f, "Ordinary or merely tagged unregistered golems never receive helper defaults");
            c.assertFalse(((AdminStatEntity)ordinary).infinity$adminStats().contains(HelperPowerDefaults.MARKER), "An unregistered golem has no migration marker");
        } c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void legacyLoadUpgradesVanillaBasesWithoutHealingOrOverwritingManualOrCustomBases(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "power-legacy")) {
            var helper = f.legacy("legacy"); helper.setHealth(37);
            helper.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(19);
            var external = Identifier.fromNamespaceAndPath("convergence", "power_test_modifier");
            helper.getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(new AttributeModifier(external, 5, AttributeModifier.Operation.ADD_VALUE));
            helper.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
            var gift = new ItemStack(Items.DIAMOND_SWORD); helper.setItemSlot(EquipmentSlot.MAINHAND, gift.copy());
            // Real AdminStats edits at exactly vanilla bases still count as intentional edits.
            f.helpers.loaded.put(helper.getUUID(), helper);
            c.assertTrue(AdminStats.set(f.owner.createCommandSourceStack(), helper, "max_health", 100).success(), "Legacy helper max-health edit is accepted");
            c.assertTrue(AdminStats.set(f.owner.createCommandSourceStack(), helper, "armor", 0).success(), "Legacy helper armor edit is accepted");
            var metadata = ((AdminStatEntity)helper).infinity$adminStats().copy(); var id = helper.getUUID();
            f.helpers.loaded.remove(id); c.getLevel().addFreshEntity(helper);
            c.assertTrue(f.helpers.loaded.get(id) == helper, "Actual native ENTITY_LOAD registers the exact migrated entity");
            c.assertValueEqual(helper.getMaxHealth(), 100f, "An edited vanilla-valued health base is protected");
            c.assertValueEqual(helper.getHealth(), 37f, "First load does not heal existing damage");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ARMOR), 0d, "An edited vanilla-valued armor base is protected");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 19d, "A custom base without AdminStats history is also protected");
            c.assertValueEqual(helper.getAttributeValue(Attributes.ATTACK_DAMAGE), 24d, "Independent persistent modifiers remain intact");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ARMOR_TOUGHNESS), 20d, "An unedited vanilla base receives the new default");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), .31d, "Custom movement is unchanged");
            c.assertTrue(ItemStack.isSameItemSameComponents(helper.getMainHandItem(), gift), "Migration does not replace native equipment");
            var after = ((AdminStatEntity)helper).infinity$adminStats().copy(); after.remove(HelperPowerDefaults.MARKER);
            c.assertValueEqual(after, metadata, "Every original-value undo record remains byte-for-byte equivalent");

            var editedDamage = f.legacy("edited-damage"); editedDamage.setPos(f.center.add(6, 0, 0)); editedDamage.setHealth(37);
            c.assertValueEqual(editedDamage.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 15d, "The legacy fixture begins at the real native vanilla attack base");
            f.helpers.loaded.put(editedDamage.getUUID(), editedDamage);
            c.assertTrue(AdminStats.set(f.owner.createCommandSourceStack(), editedDamage, "attack_damage", 15).success(),
                "An intentional edit to the exact vanilla attack value still records owner intent");
            f.helpers.loaded.remove(editedDamage.getUUID()); c.getLevel().addFreshEntity(editedDamage);
            c.assertValueEqual(editedDamage.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 15d,
                "Actual ENTITY_LOAD preserves an AdminStats-edited vanilla-valued attack base");
            c.assertValueEqual(AdminStats.original(editedDamage, AdminStats.find(editedDamage, "attack_damage")), 15d,
                "Migrating other defaults preserves the native attack undo record");
            c.assertValueEqual(editedDamage.getHealth(), 37f, "The manually edited legacy helper is not healed during migration");

            var uneditedDamage = f.legacy("unedited-damage"); uneditedDamage.setPos(f.center.add(8, 0, 0)); uneditedDamage.setHealth(37);
            c.assertValueEqual(uneditedDamage.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 15d, "The unedited comparison has the same native vanilla attack base");
            c.getLevel().addFreshEntity(uneditedDamage);
            c.assertValueEqual(uneditedDamage.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 100d,
                "Actual ENTITY_LOAD upgrades the same unedited vanilla attack value to the OP default");
            c.assertValueEqual(uneditedDamage.getHealth(), 37f, "The unedited comparison preserves its existing damage too");
        } c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void migrationMarkerAndManualResetSurviveNativeNbtPrepareRecallAndProfileChanges(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "power-save")) {
            var helper = f.fresh("saved"); helper.setHealth(123);
            c.assertTrue(AdminStats.set(f.owner.createCommandSourceStack(), helper, "attack_damage", 250).success(), "A new helper can still be edited");
            c.assertValueEqual(AdminStats.original(helper, AdminStats.find(helper, "attack_damage")), 100d, "Undo history starts at the real upgraded base");
            c.assertTrue(AdminStats.reset(f.owner.createCommandSourceStack(), helper, "attack_damage").success(), "Manual reset remains available");
            helper.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(7);
            var saved = save(helper); var copy = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
            copy.load(TagValueInput.create(ProblemReporter.DISCARDING, copy.registryAccess(), saved));
            f.helpers.unload(helper); helper.discard(); c.getLevel().addFreshEntity(copy);
            c.assertTrue(f.helpers.loaded.get(copy.getUUID()) == copy && copy.getUUID().equals(helper.getUUID()), "Native NBT reload preserves and rebinds the same persistent UUID");
            c.assertValueEqual(((AdminStatEntity)copy).infinity$adminStats().getIntOr(HelperPowerDefaults.MARKER, 0), HelperPowerDefaults.VERSION, "Migration marker survives native entity NBT");
            for (var profile : AgentCompanions.Profile.values()) {
                f.helpers.profile(f.owner, "saved", profile); AgentCompanions.prepare(copy);
                c.assertValueEqual(copy.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 7d, "Repeated preparation never reapplies over a later edit or reset");
                c.assertValueEqual(copy.getHealth(), 123f, "Profile changes never heal existing damage");
            }
            f.helpers.recall(f.owner, "saved"); var recalled = f.helpers.loaded.get(copy.getUUID());
            c.assertTrue(recalled != null && recalled.getUUID().equals(copy.getUUID()), "Explicit recall retains the persistent entity identity");
            c.assertValueEqual(recalled.getHealth(), 123f, "Recall does not refill existing health");
            c.assertValueEqual(recalled.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 7d, "Recall retains post-migration custom stats");
        } c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void defaultPowerDoesNotGrantAnyPlayerTargetApproval(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "power-gates")) {
            var helper = f.fresh("guard"); f.helpers.profile(f.owner, "guard", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(AgentCompanions.approvedPlayerTarget(helper, f.owner), "Power defaults never manufacture reviewed player targets");
            c.assertFalse(AgentCompanions.allowDamage(f.owner, helper.damageSources().mobAttack(helper)), "The independent native damage gate still refuses unapproved players");
            c.assertFalse(helper.doHurtTarget(c.getLevel(), f.owner), "Actual high-damage native melee cannot bypass the player approval gate");
            c.assertTrue(f.helpers.playerTargets.isEmpty(), "No player order is created by power migration or a profile change");
        } c.succeed();
    }
}
