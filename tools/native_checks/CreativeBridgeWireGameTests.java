package dev.convergence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Supplementary native check for exact captured Geyser packets and loaded pinned Via mods.
 * Only smoke_creative_wire.py adds this class to an isolated copy of the tested mod.
 */
public class CreativeBridgeWireGameTests {
    @GameTest public void actualGeyserPacketsPassViaNativeCodecAndAuthoritativeCreativeHandler(GameTestHelper c) throws Exception {
        String captureProperty=System.getProperty("infinity.creative.capture");
        if(captureProperty==null)throw new IllegalStateException("Pass -Dinfinity.creative.capture=<absolute ready.json>; no fabricated capture fallback");
        var capture=Files.readAllBytes(Path.of(captureProperty));
        var receipt=JsonParser.parseString(new String(capture,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        String catalogueProperty=System.getProperty("infinity.creative.catalogue");
        if(catalogueProperty==null)throw new IllegalStateException("Pass -Dinfinity.creative.catalogue=<exact captured catalogue JSON>");
        var capturedCatalogue=Files.readAllBytes(Path.of(catalogueProperty));
        c.assertValueEqual(receipt.get("catalogue_sha256").getAsString(),sha(capturedCatalogue),"Captured catalogue bytes match the actual Geyser receipt");
        var localCatalogue=Files.readAllBytes(Path.of("crossplay-export/infinity-catalogue.json"));
        var expectedCatalogue=JsonParser.parseString(new String(capturedCatalogue,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var actualCatalogue=JsonParser.parseString(new String(localCatalogue,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        c.assertValueEqual(actualCatalogue.keySet(),expectedCatalogue.keySet(),"Native catalogue retains every captured document field");
        for(var field:expectedCatalogue.entrySet())if(!field.getKey().equals("items"))
            c.assertValueEqual(actualCatalogue.get(field.getKey()),field.getValue(),"Native catalogue header matches the captured document exactly");
        c.assertValueEqual(byPath(c,actualCatalogue),byPath(c,expectedCatalogue),"Native catalogue has the exact captured entries/fields, independent of map iteration order");
        c.assertValueEqual(receipt.get("items").getAsInt(),Convergence.ITEMS.size(),"Capture covers the current canonical item set");
        var channel=new EmbeddedChannel();
        var user=new UserConnectionImpl(channel,false);
        var info=user.getProtocolInfo();
        var protocol=new Snapshot5ViaFixture(c,user);
        var player=new ModeGameTests().player(c,"wire-proof");
        OperatorGameTests.deop(player);player.setGameMode(GameType.CREATIVE);
        info.setUuid(player.getUUID());
        try {
            abilities(c,player,user,protocol);
            player.closeContainer();player.getInventory().clearContent();
            var proof=receipt.getAsJsonObject("proof");
            var expectedProtocols=receipt.getAsJsonObject("protocols").keySet();
            var validSeen=new HashSet<String>();var overflowSeen=new HashSet<String>();
            var acceptedPackets=new ArrayList<ServerboundSetCreativeModeSlotPacket>();
            int valid=0,overflow=0,survival=0,viaSurvival=0;
            for(var value:proof.getAsJsonArray("encoded_hotbar_packets")) {
                var sample=value.getAsJsonObject();String path=sample.get("path").getAsString();
                int count=sample.get("count").getAsInt();String version=sample.get("bedrock_protocol").getAsString();
                var expected=Convergence.ITEMS.get("convergence:"+path).getDefaultInstance().copyWithCount(count);
                c.assertTrue(expectedProtocols.contains(version),"Captured sample belongs to an advertised Bedrock protocol");
                c.assertTrue(count==1||count==expected.getMaxStackSize(),"Capture has only the promised valid counts");
                c.assertTrue(validSeen.add(version+":"+path+":"+count),"Capture has no duplicate hotbar case");
                var packet=translate(c,player,user,protocol,sample);
                c.assertTrue(ItemStack.matches(packet.itemStack(),expected),"Actual Geyser→Via→native→Polymer pipeline restores canonical defaults/count: "+path);
                acceptedPackets.add(new ServerboundSetCreativeModeSlotPacket(packet.slotNum(),packet.itemStack().copy()));
                player.connection.handleSetCreativeModeSlot(packet);
                c.assertTrue(ItemStack.matches(player.getInventory().getItem(0),expected),"Native Creative handler grants the exact real hotbar item: "+path);
                valid++;
            }
            for(var version:expectedProtocols)for(var entry:Convergence.ITEMS.entrySet()) {
                String path=Identifier.parse(entry.getKey()).getPath();int max=entry.getValue().getDefaultInstance().getMaxStackSize();
                c.assertTrue(validSeen.contains(version+":"+path+":1"),"Every canonical item/protocol has a real single-item selection");
                c.assertTrue(validSeen.contains(version+":"+path+":"+max),"Every canonical item/protocol has a real max-stack selection");
            }
            for(var value:proof.getAsJsonArray("encoded_oversized_packets")) {
                var sample=value.getAsJsonObject();String path=sample.get("path").getAsString();
                String version=sample.get("bedrock_protocol").getAsString();
                c.assertTrue(expectedProtocols.contains(version)&&overflowSeen.add(version+":"+path),"Each overflow case belongs to one advertised item/protocol");
                var packet=translate(c,player,user,protocol,sample);
                c.assertValueEqual(packet.itemStack().getCount(),packet.itemStack().getMaxStackSize()+1,"Actual translated overflow reaches native validation intact");
                var held=player.getInventory().getItem(0).copy();var saved=RewardTrades.serialize(player).get("Inventory");
                c.assertTrue(saved!=null,"Native overflow snapshot contains the actual inventory");
                player.connection.handleSetCreativeModeSlot(packet);
                c.assertTrue(ItemStack.matches(player.getInventory().getItem(0),held),"Native handler refuses the actual overflow packet without replacing owned items");
                c.assertValueEqual(RewardTrades.serialize(player).get("Inventory"),saved,"Actual rejected overflow never enters persisted inventory");
                overflow++;
            }
            c.assertValueEqual(overflow,Convergence.ITEMS.size()*expectedProtocols.size(),"Every current item/protocol has an authoritative overflow refusal");
            var accepted=player.getInventory().getItem(0).copy();var snapshot=RewardTrades.serialize(player);
            var restored=new ServerPlayer(player.level().getServer(),player.level(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"wire-proof-read"),player.clientInformation());
            restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,restored.registryAccess(),snapshot));
            c.assertTrue(ItemStack.matches(restored.getInventory().getItem(0),accepted),"Real translated selection retains defaults/count after native player save/load");
            player.setGameMode(GameType.SURVIVAL);
            abilities(c,player,user,protocol);
            for(var value:proof.getAsJsonArray("encoded_hotbar_packets")) {
                assertViaRefusesCreative(c,user,protocol,value.getAsJsonObject());viaSurvival++;
            }
            // Replay the exact successfully translated packets as delayed/forged Creative traffic:
            // native authority must reject them independently of Via's earlier mode guard.
            for(var packet:acceptedPackets) {
                var saved=RewardTrades.serialize(player).get("Inventory");
                c.assertTrue(saved!=null,"Native Survival snapshot contains the actual inventory");
                player.connection.handleSetCreativeModeSlot(packet);
                c.assertTrue(ItemStack.matches(player.getInventory().getItem(0),accepted),"Survival refuses an actual captured Creative selection");
                c.assertValueEqual(RewardTrades.serialize(player).get("Inventory"),saved,"Survival refusal retains persisted inventory");survival++;
            }
            var report=new JsonObject();report.addProperty("capture_sha256",sha(capture));
            report.addProperty("catalogue_sha256",sha(capturedCatalogue));
            report.addProperty("native_catalogue_sha256",sha(localCatalogue));report.addProperty("catalogue_equivalent",true);
            report.addProperty("hotbar_cases",valid);
            report.addProperty("overflow_cases",overflow);report.addProperty("survival_cases",survival);
            report.addProperty("via_survival_cases",viaSurvival);
            Snapshot5ViaFixture.dependencies(report);
            String reportProperty=System.getProperty("infinity.creative.wire.report");
            if(reportProperty==null)throw new IllegalStateException("Pass -Dinfinity.creative.wire.report=<output JSON> to bind proof to actual input/dependency bytes");
            var target=Path.of(reportProperty);Files.createDirectories(target.toAbsolutePath().getParent());Files.writeString(target,report.toString()+"\n");
        } finally {
            OperatorGameTests.deop(player);player.level().getServer().getPlayerList().remove(player);channel.finishAndReleaseAll();
        }
        c.succeed();
    }

    private ServerboundSetCreativeModeSlotPacket translate(GameTestHelper c,ServerPlayer player,UserConnectionImpl user,
        Snapshot5ViaFixture protocol,JsonObject sample) throws Exception {
        var input=Unpooled.wrappedBuffer(Base64.getDecoder().decode(sample.get("payload_base64").getAsString()));
        var output=Unpooled.buffer();
        try {
            var wrapper=PacketWrapper.create(ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT,input,user);
            protocol.transform(Direction.SERVERBOUND,State.PLAY,wrapper);
            c.assertFalse(wrapper.isCancelled(),"Actual ViaBackwards translator preserves the Creative packet");
            wrapper.writeToBuffer(output);
            var nativeBuffer=new RegistryFriendlyByteBuf(output,player.registryAccess());
            c.assertValueEqual(nativeBuffer.readVarInt(),Snapshot5ViaFixture.nativeCreativePacketId(player),"Via emits the actual native Snapshot 5 Creative packet ID");
            var packet=ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(nativeBuffer);
            c.assertFalse(nativeBuffer.isReadable(),"Native Snapshot 5 codec consumes the exact translated component payload");
            c.assertValueEqual(packet.slotNum(),(short)36,"Actual capture addresses the correct hotbar slot");
            String path=sample.get("path").getAsString();var wire=packet.itemStack();
            c.assertTrue(wire.is(CrossplaySupport.BASES.get(path)),"Via's actual item ID decodes to the expected vanilla base");
            c.assertValueEqual(wire.getCount(),sample.get("count").getAsInt(),"Via and native codec preserve the real transfer count");
            c.assertFalse(wire.get(DataComponents.ITEM_NAME).getContents() instanceof TranslatableContents,"Via preserves the actual literal name component");
            c.assertValueEqual(wire.get(DataComponents.ITEM_NAME).getString(),GearNames.label(path),"Native component ID resolves to the correct item name");
            var expectedModel=CrossplaySupport.NATIVE_BEDROCK.contains(path)
                ? CrossplaySupport.BASES.get(path).components().get(DataComponents.ITEM_MODEL) : CrossplaySupport.id(path);
            c.assertValueEqual(wire.get(DataComponents.ITEM_MODEL),expectedModel,"Native component ID resolves to the correct item model");
            var identity=wire.get(DataComponents.CUSTOM_DATA).copyTag().getCompoundOrEmpty("$polymer:stack");
            c.assertValueEqual(identity.getStringOr("id",""),"convergence:"+path,"Via preserves the trusted canonical Polymer identity component");
            c.assertValueEqual(identity.getIntOr("count",0),1,"Via preserves the default-stack identity count");
            var real=PolymerItemUtils.getRealItemStack(wire,player.registryAccess());
            c.assertTrue(ItemStack.isSameItemSameComponents(real,Convergence.ITEMS.get("convergence:"+path).getDefaultInstance()),"Native Polymer restores every canonical default component");
            return new ServerboundSetCreativeModeSlotPacket(packet.slotNum(),real);
        } finally {input.release();output.release();}
    }

    private static void abilities(GameTestHelper c,ServerPlayer player,UserConnectionImpl user,
        Snapshot5ViaFixture protocol) throws Exception {
        var bytes=Unpooled.buffer();
        try {
            var wrapper=Snapshot5ViaFixture.nativeAbilities(player,user,bytes);
            protocol.transform(Direction.CLIENTBOUND,State.PLAY,wrapper);
            c.assertFalse(wrapper.isCancelled(),"Via accepts the real native player's abilities update");
            c.assertValueEqual(protocol.canInstaBuild(),player.getAbilities().instabuild,
                "Via session tracks Creative authority from the actual native abilities packet");
        } finally {bytes.release();}
    }

    private static void assertViaRefusesCreative(GameTestHelper c,UserConnectionImpl user,
        Snapshot5ViaFixture protocol,JsonObject sample) throws Exception {
        var input=Unpooled.wrappedBuffer(Base64.getDecoder().decode(sample.get("payload_base64").getAsString()));
        try {
            var wrapper=PacketWrapper.create(ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT,input,user);
            boolean cancelled=false;
            try {protocol.transform(Direction.SERVERBOUND,State.PLAY,wrapper);}
            catch(CancelException expected) {cancelled=true;}
            c.assertTrue(cancelled&&wrapper.isCancelled(),"Via rejects the real Creative wire selection once native Survival abilities are tracked");
        } finally {input.release();}
    }

    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}

    private static TreeMap<String,JsonObject> byPath(GameTestHelper c,JsonObject catalogue) {
        var entries=new TreeMap<String,JsonObject>();
        for(var value:catalogue.getAsJsonArray("items")) {
            var entry=value.getAsJsonObject();String path=entry.get("path").getAsString();
            c.assertTrue(entries.putIfAbsent(path,entry)==null,"Catalogue has no duplicate canonical path: "+path);
        }
        return entries;
    }
}
