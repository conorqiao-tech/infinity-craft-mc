package dev.convergence;

import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;

/** Runs in its own disposable world because the pantry is world-wide saved data. */
public class UnityCommonsGameTests {
    @GameTest public void twoContributorsUnlockOnePersistentUnityGift(GameTestHelper c){
        var fixtures=new CommonsGameTests();
        var first=fixtures.survival(c,"unity-first");var second=fixtures.survival(c,"unity-second");
        try{
            first.getInventory().setItem(0,new ItemStack(Items.WHEAT,16));
            c.assertValueEqual(CommonsEvents.donate(first,CommonsEvents.quote(first,0)),16,"The first player contributes real food");
            var pantry=CommonsEvents.pantry(first);
            c.assertValueEqual(pantry.contributors(),1,"Repeated synchronization keeps one unique contributor");
            pantry.sync(first);c.assertValueEqual(pantry.contributors(),1,"A replay does not create a second contributor");
            c.assertFalse(CommonsEvents.earned(first,4),"One contributor alone cannot unlock the shared gift");
            second.getInventory().setItem(0,new ItemStack(Items.CARROT,16));
            c.assertValueEqual(CommonsEvents.donate(second,CommonsEvents.quote(second,1)),16,"Another player contributes separately");
            c.assertValueEqual(pantry.contributors(),2,"Both UUIDs count exactly once");
            var saved=CommonsEvents.Pantry.TYPE.codec().encodeStart(NbtOps.INSTANCE,pantry).getOrThrow();
            var restored=CommonsEvents.Pantry.TYPE.codec().parse(NbtOps.INSTANCE,saved).getOrThrow();
            c.assertValueEqual(restored.contributors(),2,"The saved pantry retains the two distinct contributors");
            for(int i=0;i<36;i++)first.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            c.assertValueEqual(CommonsEvents.claim(first,4),0,"A full inventory cannot lose or partially claim the banner");
            c.assertFalse(CommonsEvents.state(first).getBooleanOr("gift_4",false),"The refused claim stays available");
            first.getInventory().setItem(0,ItemStack.EMPTY);
            CommonsMenu.open(first);first.containerMenu.clicked(12,0,ContainerInput.PICKUP,first);
            c.assertValueEqual(((CommonsMenu.Handler)first.containerMenu).view.title(),"Infinity Together","Java can open the new Commons page");
            first.containerMenu.clicked(10,0,ContainerInput.PICKUP,first);first.closeContainer();
            c.assertTrue(CommonsEvents.state(first).getBooleanOr("gift_4",false),"Java claims the real gift");
            c.assertTrue(first.getInventory().getItem(0).get(DataComponents.BANNER_PATTERNS)!=null,"The gift has the original banner composition");
            c.assertValueEqual(CommonsEvents.title(first),"Unity Builder","The saved personal title is awarded");
            var reloaded=fixtures.reloadDisk(first);
            c.assertTrue(CommonsEvents.state(reloaded).getBooleanOr("gift_4",false),"Native player save retains the one-time claim");
            c.assertValueEqual(CommonsEvents.title(reloaded),"Unity Builder","Native player save retains the title");
            c.assertValueEqual(CommonsEvents.claim(first,4),0,"The claimed gift cannot be replayed");
            second.setGameMode(GameType.CREATIVE);
            c.assertValueEqual(CommonsEvents.claim(second,4),0,"Creative cannot claim the Survival reward");
            second.setGameMode(GameType.SURVIVAL);
            var forms=new ArrayList<Form>();
            CommonsMenu.form(second,CommonsMenu.Page.UNITY,null,f->{forms.add(f);return true;});var stale=forms.getLast();
            CommonsMenu.form(second,CommonsMenu.Page.UNITY,null,f->{forms.add(f);return true;});
            fixtures.reply(stale,"0");c.assertFalse(CommonsEvents.state(second).getBooleanOr("gift_4",false),"A stale Bedrock form cannot claim");
            fixtures.reply(forms.getLast(),"0");
            c.assertTrue(CommonsEvents.state(second).getBooleanOr("gift_4",false),"The current encoded Bedrock form claims once");
            fixtures.reply(forms.getLast(),"0");
            c.assertValueEqual(CommonsEvents.claim(second,4),0,"A Bedrock replay cannot duplicate the gift");
        }finally{fixtures.cleanup(first);fixtures.cleanup(second);}c.succeed();
    }
}
