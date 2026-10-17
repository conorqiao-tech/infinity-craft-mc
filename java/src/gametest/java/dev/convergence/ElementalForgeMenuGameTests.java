package dev.convergence;

import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
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
