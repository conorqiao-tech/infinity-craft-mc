package dev.convergence;

import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;

/** Native screen actions, actual inventory saves and encoded Bedrock callbacks exercise the Forge. */
public class ElementalForgeMenuGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var p = new ModeGameTests().player(c, name);
        p.closeContainer();
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        return p;
    }

    private void cleanup(ServerPlayer p) {
        ElementalForgeMenu.ACTIVE.remove(p.getUUID());
        ElementalForgeMenu.DELIVERIES.remove(p.getUUID());
        p.containerMenu.setCarried(ItemStack.EMPTY);
        p.closeContainer();
        p.level().getServer().getPlayerList().remove(p);
    }

    private void reply(Form form, String value) {
        try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form, value); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private Form wingForm(GameTestHelper c, ServerPlayer p) {
        var forms = new ArrayList<Form>();
        c.assertValueEqual(ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", form -> { forms.add(form); return true; }), 1,
            "The real native Forge form is available");
        return forms.getLast();
    }

    /** Find the label in the actual Cumulus wire form, so ordering changes cannot select another kit. */
    private int wingButton(Form form) {
        var json = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
        var wire = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        if (!wire.get("type").getAsString().equals("form") || !wire.get("title").getAsString().equals("Elemental Forge"))
            throw new IllegalStateException("The actual Forge touch form did not encode");
        var buttons = wire.getAsJsonArray("buttons");
        for (int i = 0; i < buttons.size(); i++)
            if (buttons.get(i).getAsJsonObject().get("text").getAsString().equals("Get Convergence Wings • one empty slot")) return i;
        throw new IllegalStateException("Convergence Wings are missing from the native Forge form");
    }

    private int wingCount(ServerPlayer p) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = p.getInventory().getItem(slot);
            if (stack.is(Convergence.ITEMS.get("convergence:chestplate"))) count += stack.getCount();
        }
        return count;
    }

    @GameTest public void bedrockWingsButtonDeliversSavedCombinedArmorAndNativeGlide(GameTestHelper c) {
        var p = player(c, "forge-wing-native");
        try {
            p.setGameMode(GameType.CREATIVE);
            var solid = new ItemStack(Convergence.ITEMS.get("convergence:infinity_chestplate"));
            p.setItemSlot(EquipmentSlot.CHEST, solid.copy());
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            var form = wingForm(c, p);
            var text = ElementalForgeMenu.view(p, ElementalForgeMenu.Page.HOME, "").content();
            c.assertTrue(text.contains("Solid Infinity and Elemental chestplates do not glide")
                && text.contains("chest armor slot") && text.contains("tap Jump while airborne"), "The Forge explains the actual equipment and flight controls");
            reply(form, Integer.toString(wingButton(form)));
            c.assertValueEqual(wingCount(p), 1, "The encoded native button delivers exactly one existing wing item");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(), 7, "The claim preserves the existing occupied slot");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getItemBySlot(EquipmentSlot.CHEST), solid), "Claiming wings does not replace equipped solid armor");
            var wings = p.getInventory().getItem(1).copy();
            c.assertTrue(wings.is(Convergence.ITEMS.get("convergence:chestplate")), "The delivered item keeps the existing saved registry identity");
            c.assertTrue(wings.has(DataComponents.GLIDER), "The delivered combined item retains native glide");
            c.assertValueEqual(wings.get(DataComponents.EQUIPPABLE).slot(), EquipmentSlot.CHEST, "The combined item equips in the actual chest slot");
            var disk = new CommonsGameTests().reloadDisk(p);
            c.assertTrue(ItemStack.isSameItemSameComponents(disk.getInventory().getItem(1), wings), "The actual wing delivery persists to the native player file");
            c.assertTrue(ItemStack.isSameItemSameComponents(disk.getItemBySlot(EquipmentSlot.CHEST), solid), "The saved equipped armor is unchanged by the claim");
            p.closeContainer();
            reply(form, Integer.toString(wingButton(form)));
            c.assertValueEqual(wingCount(p), 1, "Replaying the native response cannot claim a second wing item");
            p.getInventory().setItem(1, ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.CHEST, wings);
            p.setGameMode(GameType.SURVIVAL);
            var airborne = c.absolutePos(new BlockPos(2, 6, 2));
            p.setPos(airborne.getX() + 0.5, airborne.getY(), airborne.getZ() + 0.5);
            p.setOnGround(false);
            p.setDeltaMovement(0, -0.4, 0);
            c.assertTrue(p.tryToStartFallFlying(), "The actual delivered wing item starts native Survival glide after equipping");
            c.assertTrue(p.isFallFlying(), "The native player gliding flag is active");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void bedrockWingsButtonRefusesFullInventoryAndPreservesOwnedItems(GameTestHelper c) {
        var p = player(c, "forge-wing-full");
        try {
            p.setGameMode(GameType.CREATIVE);
            var solid = new ItemStack(Convergence.ITEMS.get("convergence:infinity_chestplate"));
            p.setItemSlot(EquipmentSlot.CHEST, solid.copy());
            var owned = new ItemStack(Items.DIAMOND, 64);
            owned.set(DataComponents.CUSTOM_NAME, Component.literal("Keep my collection"));
            for (int slot = 0; slot < 36; slot++) p.getInventory().setItem(slot, owned.copy());
            var form = wingForm(c, p);
            reply(form, Integer.toString(wingButton(form)));
            c.assertValueEqual(wingCount(p), 0, "The native claim refuses when no empty inventory slot exists");
            for (int slot = 0; slot < 36; slot++)
                c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(slot), owned)
                    && p.getInventory().getItem(slot).getCount() == 64, "A refused wing claim preserves the exact owned stack in slot " + slot);
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getItemBySlot(EquipmentSlot.CHEST), solid), "The refused claim cannot change worn armor");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void bedrockWingsButtonRechecksCreativeAccessAndRejectsOccupiedCursor(GameTestHelper c) {
        var p = player(c, "forge-wing-access");
        try {
            p.setGameMode(GameType.CREATIVE);
            var revoked = wingForm(c, p);
            p.setGameMode(GameType.SURVIVAL);
            reply(revoked, Integer.toString(wingButton(revoked)));
            c.assertTrue(p.getInventory().isEmpty(), "Revoked Creative permission refuses the pending wing claim");
            p.closeContainer();
            p.setGameMode(GameType.CREATIVE);
            var carried = wingForm(c, p);
            p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 3));
            reply(carried, Integer.toString(wingButton(carried)));
            c.assertTrue(p.getInventory().isEmpty(), "An occupied cursor refuses the pending native wing claim");
            c.assertValueEqual(p.inventoryMenu.getCarried().getCount(), 3, "The refused claim preserves the cursor item");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void javaWingsButtonUsesSameSingleDeliveryWithoutAutoEquipping(GameTestHelper c) {
        var p = player(c, "forge-wing-java");
        try {
            p.setGameMode(GameType.CREATIVE);
            var solid = new ItemStack(Convergence.ITEMS.get("convergence:elemental_chestplate"));
            p.setItemSlot(EquipmentSlot.CHEST, solid.copy());
            c.assertValueEqual(ElementalForgeMenu.open(p), 1, "Java opens the same Forge home");
            var menu = (ElementalForgeMenu.Handler)p.containerMenu;
            int button = -1;
            for (int i = 0; i < menu.view.choices().size(); i++)
                if (menu.view.choices().get(i).label().equals("Get Convergence Wings • one empty slot")) button = i;
            c.assertTrue(button >= 0, "Java exposes the same named Convergence Wings action");
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE + button, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(wingCount(p), 1, "The Java control delivers one actual combined wing item");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getItemBySlot(EquipmentSlot.CHEST), solid), "The Java claim preserves existing equipped armor");
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE + button, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(wingCount(p), 1, "A stale Java screen packet cannot repeat the claim");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void survivalCanDiscoverAndAttuneWithoutReceivingFreeGear(GameTestHelper c) {
        var p = player(c, "forge-attune");
        try {
            var before = GameModes.state(p).copy();
            c.assertValueEqual(ElementalForgeMenu.open(p), 1, "Survival can open the real Forge");
            var menu = (ElementalForgeMenu.Handler)p.containerMenu;
            c.assertValueEqual(GameModes.state(p), before, "Viewing the Forge does not create or change selection data");
            c.assertTrue(menu.view.content().contains("Mixed sets do not activate"), "Full matching set rule is visible");
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE + 1, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.WATER, "Water button saves an actual attunement");
            c.assertTrue(p.getInventory().isEmpty(), "Ordinary attunement never grants equipment");
            p.closeContainer();
            c.assertValueEqual(ElementalForgeMenu.claim(p, ElementalForgeMenu.Kit.INFINITY), 0, "Survival cannot claim free ultimate gear");
            c.assertTrue(p.getInventory().isEmpty(), "Rejected claim preserves inventory");
            var disk = new CommonsGameTests().reloadDisk(p);
            c.assertValueEqual(ElementalArmor.selected(disk), ElementalArmor.Element.WATER, "Selection survives a real native disk reload");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void kitsUseRealItemsAndAtomicEmptySlotDeliveryWithDiskPersistence(GameTestHelper c) {
        var p = player(c, "forge-claim");
        try {
            p.setGameMode(GameType.CREATIVE);
            for (int slot = 0; slot < 36; slot++) p.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
            for (int slot = 0; slot < 3; slot++) p.getInventory().setItem(slot, ItemStack.EMPTY);
            c.assertValueEqual(ElementalForgeMenu.claim(p, ElementalForgeMenu.Kit.ELEMENTAL), 0, "Three empty slots refuse the entire four-piece kit");
            c.assertTrue(p.getInventory().getItem(0).isEmpty(), "A refused kit cannot partly deliver");
            c.assertValueEqual(p.getInventory().getItem(35).getCount(), 64, "Owned items are not replaced or dropped");
            p.getInventory().setItem(3, ItemStack.EMPTY);
            c.assertValueEqual(ElementalForgeMenu.claim(p, ElementalForgeMenu.Kit.ELEMENTAL), 4, "Four free slots receive all four actual armor pieces");
            var expected = ElementalArmor.kit(ElementalArmor.Suit.ELEMENTAL);
            var disk = new CommonsGameTests().reloadDisk(p);
            for (int slot = 0; slot < 4; slot++)
                c.assertTrue(ItemStack.isSameItemSameComponents(disk.getInventory().getItem(slot), expected.get(slot)), "Claimed armor survives actual native save in slot " + slot);
            p.getInventory().clearContent();
            c.assertValueEqual(ElementalForgeMenu.claim(p, ElementalForgeMenu.Kit.BLOCKS), 5, "Block kit contains all five original styles");
            for (int slot = 0; slot < 5; slot++) c.assertValueEqual(p.getInventory().getItem(slot).getCount(), 64, "Each block style is a real full stack");
            p.getInventory().clearContent();
            c.assertValueEqual(ElementalForgeMenu.claim(p, ElementalForgeMenu.Kit.RELICS), 4, "All four actual relics are delivered");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void javaForgeCannotYieldIconsOrActThroughAnotherScreen(GameTestHelper c) {
        var p = player(c, "forge-java");
        var other = player(c, "forge-foreign");
        try {
            ElementalForgeMenu.open(p);
            var menu = (ElementalForgeMenu.Handler)p.containerMenu;
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE + 2, 0, ContainerInput.PICKUP, other);
            c.assertTrue(p.containerMenu == menu, "Foreign click cannot close or change another player's Forge");
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE, 0, ContainerInput.THROW, p);
            c.assertTrue(menu.getCarried().isEmpty() && p.getInventory().isEmpty(), "Forge controls cannot be thrown into inventory");
            menu.setCarried(new ItemStack(Items.EMERALD, 3));
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE + 2, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "Occupied cursor refuses a Forge action");
            menu.setCarried(ItemStack.EMPTY);
            p.closeContainer();
            ServerMenu.open(p);
            var current = p.containerMenu;
            menu.clicked(ElementalForgeMenu.FIRST_CHOICE + 2, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu == current, "Old Forge packet cannot act through a replacement screen");
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "Stale click cannot alter attunement");
        } finally { cleanup(p); cleanup(other); }
        c.succeed();
    }

    @GameTest public void bedrockFormsAreSessionBoundAndSingleUse(GameTestHelper c) {
        var p = player(c, "forge-native");
        var other = player(c, "forge-session");
        try {
            var forms = new ArrayList<Form>();
            java.util.function.Predicate<Form> sender = form -> { forms.add(form); return true; };
            ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", sender);
            var stale = forms.getLast();
            ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", sender);
            reply(stale, "2");
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "Superseded native form is refused");
            var active = forms.getLast();
            reply(active, "2");
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.EARTH, "Current encoded native form activates Earth");
            p.closeContainer();
            reply(active, "1");
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.EARTH, "Replayed response cannot choose another element");
            ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", sender);
            var foreign = forms.getLast();
            var connection = p.connection;
            try { p.connection = other.connection; reply(foreign, "3"); }
            finally { p.connection = connection; }
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.EARTH, "Replacement connection cannot reuse an old form");
        } finally { cleanup(p); cleanup(other); }
        c.succeed();
    }

    @GameTest public void bedrockClaimRechecksPermissionAndRejectsCursorAndExpiredSessions(GameTestHelper c) {
        var p = player(c, "forge-recheck");
        try {
            var forms = new ArrayList<Form>();
            java.util.function.Predicate<Form> sender = form -> { forms.add(form); return true; };
            p.setGameMode(GameType.CREATIVE);
            ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", sender);
            p.setGameMode(GameType.SURVIVAL);
            reply(forms.getLast(), "4");
            c.assertTrue(p.getInventory().isEmpty(), "Revoked Creative access cannot claim armor from the old native form");
            p.closeContainer();
            ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", sender);
            p.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            reply(forms.getLast(), "1");
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "Native callback with an occupied cursor cannot attune");
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
            ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", sender);
            var old = ElementalForgeMenu.ACTIVE.get(p.getUUID());
            var expired = new ElementalForgeMenu.Ticket(old.nonce(), p, p.connection, p.level(), GameModes.current(p), p.level().getServer().getTickCount());
            ElementalForgeMenu.ACTIVE.put(p.getUUID(), expired);
            ElementalForgeMenu.response(expired, () -> ElementalArmor.select(p, ElementalArmor.Element.AIR));
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "Expired native session fails closed");
            c.assertValueEqual(ElementalForgeMenu.form(p, ElementalForgeMenu.Page.HOME, "", form -> false), 0, "Failed form send reports failure");
            c.assertFalse(ElementalForgeMenu.ACTIVE.containsKey(p.getUUID()), "Failed send releases its nonce");
        } finally { cleanup(p); }
        c.succeed();
    }
}
