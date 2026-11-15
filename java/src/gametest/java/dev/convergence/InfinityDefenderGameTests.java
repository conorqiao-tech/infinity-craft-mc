package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;

public class InfinityDefenderGameTests {
    static void cleanup(CommonsGameTests f, ServerPlayer p) {
        InfinityJourney.ACTIVE.remove(p.getUUID()); InfinityJourney.DELIVERIES.remove(p.getUUID()); f.cleanup(p);
    }
    static LivingEntity zombie(ServerPlayer p) {
        var mob = EntityTypes.ZOMBIE.create(p.level(), EntitySpawnReason.COMMAND);
        mob.setPos(p.position().add(3, 0, 0)); p.level().addFreshEntity(mob); return mob;
    }
    static void defeat(ServerPlayer p, LivingEntity mob) {
        mob.setHealth(1); mob.hurtServer(p.level(), p.level().damageSources().playerAttack(p), 10);
    }

    @GameTest public void hostileFamiliesHaveStableDistinctEntries(GameTestHelper c) {
        var types = List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER,
            EntityTypes.ENDERMAN, EntityTypes.BLAZE, EntityTypes.GUARDIAN, EntityTypes.WITHER_SKELETON);
        for (int i = 0; i < types.size(); i++) c.assertValueEqual(InfinityDefender.type(types.get(i)), i, "Each hostile family has a distinct entry");
        c.assertValueEqual(InfinityDefender.type(EntityTypes.HUSK), 0, "Husks count with zombies");
        c.assertValueEqual(InfinityDefender.type(EntityTypes.CAVE_SPIDER), 2, "Cave spiders count with spiders");
        c.assertValueEqual(InfinityDefender.type(EntityTypes.IRON_GOLEM), -1, "Iron-golem helpers are never a Defender target"); c.succeed();
    }

    @GameTest public void actualDeathsCountOnlyAfterOptInAndLeaveHelpersAlone(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "defender-real");
        var mobs = new ArrayList<LivingEntity>();
        try {
            var first = zombie(p); mobs.add(first); defeat(p, first);
            c.assertFalse(GameModes.state(p).contains(InfinityDefender.KEY), "A normal death before opt-in creates no Defender state");
            c.assertValueEqual(InfinityDefender.setActive(p, true), 1, "The player starts Defender");
            var next = zombie(p); mobs.add(next); defeat(p, next);
            c.assertFalse(next.isAlive(), "The native combat outcome still kills the hostile");
            c.assertValueEqual(InfinityDefender.defeated(p), 1, "The real player-caused death records the zombie family");
            c.assertValueEqual(InfinityDefender.defeated(f.reloadDisk(p)), 1, "The native player save retains progress");
            var repeated = zombie(p); mobs.add(repeated); defeat(p, repeated);
            c.assertValueEqual(InfinityDefender.defeated(p), 1, "Another zombie cannot duplicate progress");
            var helper = EntityTypes.IRON_GOLEM.create(p.level(), EntitySpawnReason.COMMAND);
            helper.addTag(AgentCompanions.TAG); helper.setPos(p.position().add(5, 0, 0)); p.level().addFreshEntity(helper); mobs.add(helper);
            float health = helper.getHealth(); var tags = java.util.Set.copyOf(helper.entityTags());
            c.assertFalse(InfinityDefender.observe(p, helper), "A helper-like body is never counted");
            c.assertValueEqual(helper.getHealth(), health, "The helper-like body's health is unchanged");
            c.assertValueEqual(helper.entityTags(), tags, "The helper-like body's tags are unchanged");
            var friend = zombie(p); mobs.add(friend); friend.addTag("convergence_friend");
            c.assertFalse(InfinityDefender.observe(p, friend), "A tagged friend is excluded even if it is a hostile type");
            p.setGameMode(GameType.CREATIVE); var creeper = EntityTypes.CREEPER.create(p.level(), EntitySpawnReason.COMMAND);
            creeper.setPos(p.position().add(3, 0, 0)); p.level().addFreshEntity(creeper); mobs.add(creeper); defeat(p, creeper);
            c.assertValueEqual(InfinityDefender.defeated(p), 1, "Creative combat does not advance Defender");
            p.setGameMode(GameType.SURVIVAL); c.assertValueEqual(InfinityDefender.setActive(p, false), 1, "Defender can be paused");
            c.assertValueEqual(InfinityDefender.defeated(p), 1, "Pausing keeps the prior progress");
        } finally { for (var mob : mobs) mob.discard(); cleanup(f, p); } c.succeed();
    }

    @GameTest public void unknownDataAndFullInventoryArePreserved(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "defender-data");
        try {
            var root = GameModes.state(p); root.putString(InfinityDefender.KEY, "future data"); var before = root.copy();
            c.assertValueEqual(InfinityDefender.setActive(p, true), 0, "Unknown state is refused");
            c.assertValueEqual(root, before, "Unknown state is retained");
            var complete = new CompoundTag(); complete.putInt("format", 1); complete.putInt("defeated", InfinityDefender.ALL); complete.putBoolean("active", false); root.put(InfinityDefender.KEY, complete);
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            c.assertValueEqual(InfinityDefender.giveBanner(p), 0, "A full inventory refuses the banner");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(), 64, "No old item is replaced");
            p.getInventory().setItem(0, ItemStack.EMPTY);
            c.assertValueEqual(InfinityDefender.giveBanner(p), 1, "The earned banner goes into an empty slot");
            c.assertTrue(p.getInventory().getItem(0).get(DataComponents.BANNER_PATTERNS) != null, "The banner has its native pattern");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(0), f.reloadDisk(p).getInventory().getItem(0)), "The native save retains the banner");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void javaAndBedrockDefenderControlsRejectStaleChoices(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "defender-menu");
        try {
            InfinityJourney.open(p); p.containerMenu.clicked(36, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(((InfinityJourney.Handler) p.containerMenu).view.title(), "Infinity Defender", "Java opens Defender through Journey");
            p.containerMenu.clicked(27, 0, ContainerInput.PICKUP, p); p.closeContainer();
            c.assertTrue(InfinityDefender.active(p), "The Java button starts Defender");
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.DEFENDER, form -> { forms.add(form); return true; });
            InfinityJourney.form(p, InfinityJourney.Page.DEFENDER, form -> { forms.add(form); return true; });
            f.reply(forms.get(0), "0"); c.assertTrue(InfinityDefender.active(p), "An old Bedrock form cannot pause Defender");
            f.reply(forms.get(1), "0"); c.assertFalse(InfinityDefender.active(p), "The current encoded response pauses Defender");
            p.closeContainer(); forms.clear();
            InfinityJourney.form(p, InfinityJourney.Page.DEFENDER, form -> { forms.add(form); return true; });
            c.assertValueEqual(InfinityDefender.setActive(p, true), 1, "Defender changed while the form was open");
            f.reply(forms.get(0), "0"); c.assertTrue(InfinityDefender.active(p), "A stale Start label cannot unexpectedly pause it");
        } finally { cleanup(f, p); } c.succeed();
    }
}
