package dev.convergence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.viaversion.viabackwards.protocol.v26_3to26_2.Protocol26_3To26_2;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ServerboundPackets26_3;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.storage.TagValueInput;

/** Opt-in proof, added only to a disposable copy of the already-tested native jar.
 * Actual framed ingress supplies Fabric's PacketContext and Polymer's receive hook;
 * this class never calls a Creative listener or a Polymer restoration utility.
 */
public class CreativeBridgePersistenceGameTests {
    @GameTest(maxTicks=1200)
    public void actualInboundCreativeSelectionsSurviveNativeDiskAndSavedReconnect(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c,()->new Scenario(c));
    }

    private static final class Scenario extends NativeSessionFixture {
        final byte[] captureBytes;
        final byte[] catalogueBytes;
        final JsonObject capture;
        final List<JsonObject> samples=new ArrayList<>();
        final List<JsonObject> distinct=new ArrayList<>();
        final List<Integer> collectionSlots=new ArrayList<>();
        final HashSet<String> persistedPaths=new HashSet<>();
        final Protocol26_3To26_2 protocol;
        Joined owner,observer;
        EmbeddedChannel viaChannel;
        UserConnectionImpl via;
        byte[] observerBefore;
        ItemStack expectedInbound;
        int observed,hotbarCases,diskCases,rejoinCases,rejoins,variantInputs;
        String nativeCatalogueSha;

        Scenario(GameTestHelper c) {
            super(c);
            captureBytes=read(required("infinity.creative.capture"));
            catalogueBytes=read(required("infinity.creative.catalogue"));
            capture=parse(captureBytes);
            c.assertValueEqual(capture.get("catalogue_sha256").getAsString(),sha(catalogueBytes),"Captured catalogue matches its real Geyser capture");
            var nativeBytes=read(Path.of("crossplay-export/infinity-catalogue.json"));
            nativeCatalogueSha=sha(nativeBytes);
            var actual=parse(nativeBytes);var expected=parse(catalogueBytes);
            c.assertValueEqual(actual.keySet(),expected.keySet(),"Native catalogue retains every captured document field");
            for(var field:expected.entrySet())if(!field.getKey().equals("items"))
                c.assertValueEqual(actual.get(field.getKey()),field.getValue(),"Native catalogue header matches the capture");
            c.assertValueEqual(byPath(c,actual),byPath(c,expected),"All native catalogue entry fields match, independent of iteration order");
            c.assertValueEqual(capture.get("items").getAsInt(),Convergence.ITEMS.size(),"Capture covers the current canonical set");
            var byCase=new TreeMap<String,JsonObject>();var seen=new HashSet<String>();
            for(var value:capture.getAsJsonObject("proof").getAsJsonArray("encoded_hotbar_packets")) {
                var sample=value.getAsJsonObject();String path=sample.get("path").getAsString();int count=sample.get("count").getAsInt();
                String bedrock=sample.get("bedrock_protocol").getAsString();
                c.assertTrue(capture.getAsJsonObject("protocols").has(bedrock),"Captured packet uses an advertised Bedrock protocol");
                c.assertTrue(seen.add(bedrock+":"+path+":"+count),"Capture has no duplicate protocol/item/count case");
                var item=Convergence.ITEMS.get("convergence:"+path);
                c.assertTrue(item!=null,"Every capture path is a current canonical item");
                c.assertTrue(count==1||count==item.getDefaultInstance().getMaxStackSize(),"Every packet has a promised valid count");
                samples.add(sample);byCase.putIfAbsent(path+":"+count,sample);
            }
            for(var bedrock:capture.getAsJsonObject("protocols").keySet())for(var entry:Convergence.ITEMS.entrySet()) {
                String path=Identifier.parse(entry.getKey()).getPath();int max=entry.getValue().getDefaultInstance().getMaxStackSize();
                c.assertTrue(seen.contains(bedrock+":"+path+":1")&&seen.contains(bedrock+":"+path+":"+max),"Each item/protocol has both applicable counts");
            }
            distinct.addAll(byCase.values());
            c.assertValueEqual(samples.size(),180,"All 180 real captured hotbar inputs are required");
            c.assertValueEqual(distinct.size(),60,"All 60 distinct item/count disk cases are required");
            c.assertValueEqual(Convergence.ITEMS.size(),46,"This proof covers all 46 canonical identities");
            for(int slot=1;slot<36;slot++)if(slot!=6&&slot!=7&&slot!=8&&slot!=34&&slot!=35)collectionSlots.add(slot);
            c.assertValueEqual(collectionSlots.size(),30,"Two complete batches fit around the unchanged fixture controls");
            var manager=Via.getManager().getProtocolManager();manager.completeMappingDataLoading(Protocol26_3To26_2.class);
            protocol=manager.getProtocol(Protocol26_3To26_2.class);
            c.assertTrue(protocol!=null,"The real pinned ViaBackwards protocol is registered");
        }

        @Override void start() {
            owner=join("persist-owner",2);observer=join("persist-watch",5);
            OperatorGameTests.deop(owner.player);OperatorGameTests.deop(observer.player);
            // Warm only the fixture's actual Creative destination; no world/save is borrowed.
            var place=GameModes.defaultPlace(server,GameModes.Mode.CREATIVE);
            var world=GameModes.world(server,GameModes.Mode.CREATIVE);
            int cx=((int)Math.floor(place.x()))>>4,cz=((int)Math.floor(place.z()))>>4;
            for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)world.getChunk(x,z);
            GameModes.switchNow(owner.player,GameModes.Mode.CREATIVE,null);owner.drain();
            awaitCreative(100);
        }

        void awaitCreative(int remaining) {
            c.assertTrue(remaining>0,"Native Creative mode preparation stays bounded");
            if(GameModes.PENDING.containsKey(owner.player.getUUID())){later(1,()->awaitCreative(remaining-1));return;}
            c.assertTrue(owner.player.isCreative()&&GameModes.current(owner.player)==GameModes.Mode.CREATIVE
                &&GameModes.of(owner.player.level())==GameModes.Mode.CREATIVE,"Fixture enters the actual separate Creative world");
            // Let ordinary JOIN/menu recovery settle before the observer's file snapshot.
            later(6,this::beginProof);
        }

        void beginProof() {
            owner.player.closeContainer();owner.player.getInventory().clearContent();
            for(var slot:EquipmentSlot.values())if(slot!=EquipmentSlot.MAINHAND)owner.player.setItemSlot(slot,ItemStack.EMPTY);
            owner.player.getInventory().setItem(7,ServerMenu.navigator(owner.player));
            owner.player.getInventory().setItem(8,CreativeGearPicker.picker());
            owner.player.getInventory().setSelectedSlot(0);
            attachActualIngress();
            // All special component variants begin with a real accepted selection.
            var weapon=admit(sample("sword"));variantInputs++;
            weapon.remove(DataComponents.UNBREAKABLE); // Component-variant fixture, retaining the accepted canonical identity.
            c.assertTrue(weapon.isDamageableItem(),"Renamed fixture weapon supports actual native damage");
            weapon.setDamageValue(7);weapon.set(DataComponents.CUSTOM_NAME,Component.literal("Odyssey • Saved blade"));
            moveScratch(6);
            for(int i=0;i<BackpackStorage.STYLES.size();i++) {
                var style=BackpackStorage.STYLES.get(i);var stack=admit(sample("backpack"));variantInputs++;
                stack.set(DataComponents.DYED_COLOR,new DyedItemColor(style.color()));
                stack.set(DataComponents.CUSTOM_NAME,Component.literal(style.name()));
                if(i<2)moveScratch(34+i);
                else {owner.player.setItemSlot(EquipmentSlot.CHEST,stack);owner.player.getInventory().setItem(0,ItemStack.EMPTY);}
            }
            var treasure=admit(sample("mace"));variantInputs++;
            treasure.remove(DataComponents.UNBREAKABLE);
            c.assertTrue(treasure.isDamageableItem(),"Stored fixture weapon supports actual native damage");
            treasure.setDamageValue(9);treasure.set(DataComponents.CUSTOM_NAME,Component.literal("Odyssey • Backpack relic"));
            var expectedTreasure=treasure.copy();
            c.assertValueEqual(BackpackStorage.open(owner.player),1,"The connected Creative player opens the actual personal backpack");
            c.assertTrue(owner.player.containerMenu instanceof ChestMenu,"Personal storage uses the real native chest handler");
            var bag=(ChestMenu)owner.player.containerMenu;bag.quickMoveStack(owner.player,54);
            c.assertTrue(owner.player.getInventory().getItem(0).isEmpty(),"Native backpack transfer debits the selected item");
            c.assertTrue(ItemStack.matches(bag.getContainer().getItem(0),expectedTreasure),"Native backpack transfer credits all relic components/count");
            owner.player.closeContainer();
            PlayerPersistence.save(observer.player);observerBefore=read(GameModes.savedPlayerFile(server,observer.player.getUUID()));
            GameModes.state(observer.player).putString("unsaved_observer","must stay only in memory");
            for(var sample:samples){admit(sample);hotbarCases++;owner.player.getInventory().setItem(0,ItemStack.EMPTY);}
            assertObserver();runBatch(0);
        }

        JsonObject sample(String path) {
            return samples.stream().filter(s->s.get("path").getAsString().equals(path)&&s.get("count").getAsInt()==1)
                .findFirst().orElseThrow(()->new IllegalStateException("Required actual variant capture missing: "+path));
        }

        void attachActualIngress() {
            if(viaChannel!=null)viaChannel.finishAndReleaseAll();
            viaChannel=new EmbeddedChannel();via=new UserConnectionImpl(viaChannel,false);
            var info=via.getProtocolInfo();info.setProtocolVersion(ProtocolVersion.v26_2);info.setServerProtocolVersion(ProtocolVersion.v26_3);
            info.setClientState(State.PLAY);info.setServerState(State.PLAY);info.setUuid(owner.player.getUUID());
            var pipeline=new ProtocolPipelineImpl(via);pipeline.add(protocol);abilities();
            owner.wire.channel.pipeline().addBefore("packet_handler","observe_canonical_creative",new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext context,Object value) throws Exception {
                    if(value instanceof ServerboundSetCreativeModeSlotPacket packet) {
                        c.assertTrue(expectedInbound!=null,"Only a currently submitted real Creative frame is observed");
                        c.assertValueEqual(packet.slotNum(),(short)36,"The real framed decoder retains the captured hotbar slot");
                        c.assertTrue(ItemStack.matches(packet.itemStack(),expectedInbound),"Fabric packet context and Polymer receive hook restore all canonical defaults before the native listener");
                        observed++;
                    }
                    context.fireChannelRead(value);
                }
            });
        }

        void abilities() {
            var bytes=Unpooled.buffer();
            try {
                ClientboundPlayerAbilitiesPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(bytes,owner.player.registryAccess()),new ClientboundPlayerAbilitiesPacket(owner.player.getAbilities()));
                var wrapper=PacketWrapper.create(ClientboundPackets26_3.PLAYER_ABILITIES,bytes,via);
                protocol.transform(Direction.CLIENTBOUND,State.PLAY,wrapper);
                c.assertFalse(wrapper.isCancelled(),"Pinned Via accepts actual native abilities");
                c.assertTrue(protocol.getEntityRewriter().tracker(via).canInstaBuild(),"Actual native abilities authorize the Via Creative session");
            }catch(Exception error){throw new IllegalStateException("Actual native abilities translation failed",error);}
            finally{bytes.release();}
        }

        ItemStack admit(JsonObject sample) {
            String path=sample.get("path").getAsString();int count=sample.get("count").getAsInt();
            c.assertTrue(owner.player.containerMenu==owner.player.inventoryMenu&&owner.player.containerMenu.getCarried().isEmpty(),"Framed Creative selection has no open-container/cursor ambiguity");
            c.assertTrue(owner.wire.connection.isConnected()&&server.getPlayerList().getPlayer(owner.player.getUUID())==owner.player,"Framed input uses the exact current native session");
            expectedInbound=Convergence.ITEMS.get("convergence:"+path).getDefaultInstance().copyWithCount(count);
            var input=Unpooled.wrappedBuffer(Base64.getDecoder().decode(sample.get("payload_base64").getAsString()));
            var translated=Unpooled.buffer();
            try {
                var wrapper=PacketWrapper.create(ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT,input,via);
                protocol.transform(Direction.SERVERBOUND,State.PLAY,wrapper);
                c.assertFalse(wrapper.isCancelled(),"Actual ViaBackwards preserves the accepted Creative input");wrapper.writeToBuffer(translated);
                // This unscoped decode proves the wire still contains its vanilla base.
                // The native pipeline below must independently restore it using the actual receiving connection context.
                var verify=new RegistryFriendlyByteBuf(translated.duplicate(),owner.player.registryAccess());
                c.assertValueEqual(verify.readVarInt(),ServerboundPackets26_3.SET_CREATIVE_MODE_SLOT.getId(),"Via emits the actual native Creative packet ID");
                var wire=ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(verify).itemStack();
                c.assertFalse(verify.isReadable(),"Actual native codec consumes every translated component byte");
                c.assertTrue(wire.is(CrossplaySupport.BASES.get(path)),"Pre-context native decode still contains the actual vanilla wire base");
                c.assertValueEqual(wire.getCount(),count,"Via preserves the exact captured count");
                var identity=wire.get(DataComponents.CUSTOM_DATA).copyTag().getCompoundOrEmpty("$polymer:stack");
                c.assertValueEqual(identity.getStringOr("id",""),"convergence:"+path,"Via preserves the exact trusted Polymer identity");
                int before=observed;var frame=Unpooled.buffer();
                VarInt.write(frame,translated.readableBytes());frame.writeBytes(translated,translated.readerIndex(),translated.readableBytes());
                owner.wire.channel.writeInbound(frame);owner.wire.channel.runPendingTasks();owner.wire.channel.checkException();owner.drain();
                c.assertValueEqual(observed,before+1,"Actual framed native inbound decoding observes exactly one canonical Creative packet");
                var granted=owner.player.getInventory().getItem(0);
                c.assertTrue(ItemStack.matches(granted,expectedInbound),"The real Connection/native listener grants the canonical stack without a direct handler call");
                c.assertFalse(granted.get(DataComponents.ITEM_NAME).getContents() instanceof TranslatableContents,"Granted default item name stays literal");
                c.assertValueEqual(granted.get(DataComponents.ITEM_NAME).getString(),GearNames.label(path),"Granted default item has its readable canonical name");
                return granted;
            }catch(Exception error){throw new IllegalStateException("Framed Creative ingress failed for "+path+":"+count,error);}
            finally{expectedInbound=null;input.release();translated.release();}
        }

        void moveScratch(int destination) {
            c.assertTrue(owner.player.getInventory().getItem(destination).isEmpty(),"Fixture collection never overwrites owned inventory");
            // Test-fixture collection moves the actual accepted reference, not a fabricated grant or client drag claim.
            owner.player.getInventory().setItem(destination,owner.player.getInventory().getItem(0));
            owner.player.getInventory().setItem(0,ItemStack.EMPTY);
        }

        void runBatch(int batch) {
            for(int i=0;i<30;i++) {
                var sample=distinct.get(batch*30+i);admit(sample);moveScratch(collectionSlots.get(i));
                persistedPaths.add(sample.get("path").getAsString());
            }
            var snapshot=new Snapshot(owner.player);PlayerPersistence.save(owner.player);
            var file=GameModes.savedPlayerFile(server,owner.player.getUUID());
            var disk=readNbt(file);
            c.assertValueEqual(disk.getCompoundOrEmpty("InfinityModes"),snapshot.modes,"Real disk NBT contains the exact mode/backpack state");
            var restored=new ServerPlayer(server,owner.player.level(),owner.player.getGameProfile(),owner.player.clientInformation());
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING,restored.registryAccess(),disk));
            snapshot.assertMatches(c,restored,"actual disk read");diskCases+=30;assertObserver();
            var oldPlayer=owner.player;var oldListener=owner.player.connection;
            reconnect(owner,fresh->{
                owner=fresh;
                c.assertTrue(owner.player!=oldPlayer&&owner.player.connection!=oldListener,"Saved reconnect creates a genuinely fresh native player/listener");
                c.assertTrue(server.getPlayerList().getPlayer(owner.player.getUUID())==owner.player,"Native PlayerList installs the exact saved same-UUID replacement");
                snapshot.assertMatches(c,owner.player,"genuine saved reconnect");rejoinCases+=30;rejoins++;assertObserver();
                c.assertValueEqual(BackpackStorage.open(owner.player),1,"Fresh saved session reopens its real personal backpack");
                var bag=((ChestMenu)owner.player.containerMenu).getContainer();
                for(int i=0;i<BackpackStorage.SIZE;i++)c.assertTrue(ItemStack.matches(bag.getItem(i),snapshot.backpack.get(i)),"Fresh backpack menu retains exact sparse items/components at slot "+i);
                owner.player.closeContainer();attachActualIngress();
                for(var slot:collectionSlots)owner.player.getInventory().setItem(slot,ItemStack.EMPTY);
                if(batch==0)runBatch(1);else finishProof();
            });
        }

        void assertObserver() {
            c.assertTrue(Arrays.equals(observerBefore,read(GameModes.savedPlayerFile(server,observer.player.getUUID()))),"Participant save/rejoin never rewrites the unrelated observer's native file");
            c.assertValueEqual(GameModes.state(observer.player).getStringOr("unsaved_observer",""),"must stay only in memory","Unrelated online observer keeps its unsaved state");
        }

        void finishProof() {
            c.assertValueEqual(hotbarCases,180,"Every captured protocol/item/count frame was accepted through real ingress");
            c.assertValueEqual(diskCases,60,"Both actual native file saves retain every distinct item/count case");
            c.assertValueEqual(rejoinCases,60,"Both genuine native saved reconnects retain every distinct item/count case");
            c.assertValueEqual(persistedPaths.size(),46,"Actual file saves and reconnects cover every canonical identity");
            c.assertValueEqual(rejoins,2,"Two genuine same-UUID saved logins completed");
            c.assertValueEqual(observed,hotbarCases+diskCases+variantInputs,"Every admitted input used real framed decoding/hook observation");assertObserver();
            var report=new JsonObject();report.addProperty("capture_sha256",sha(captureBytes));report.addProperty("catalogue_sha256",sha(catalogueBytes));
            report.addProperty("native_catalogue_sha256",nativeCatalogueSha);report.addProperty("catalogue_equivalent",true);
            report.addProperty("hotbar_cases",hotbarCases);report.addProperty("distinct_item_count_cases",distinct.size());
            report.addProperty("disk_cases",diskCases);report.addProperty("rejoin_cases",rejoinCases);report.addProperty("canonical_identities",persistedPaths.size());
            report.addProperty("native_save_batches",2);report.addProperty("genuine_reconnects",rejoins);report.addProperty("variant_inputs",variantInputs);
            report.addProperty("framed_inbound_packets",observed);report.addProperty("receive_hook_canonical_before_listener",true);
            report.addProperty("renamed_damaged_weapon_preserved",true);report.addProperty("backpack_styles_preserved",3);
            report.addProperty("personal_backpack_preserved",true);report.addProperty("observer_file_unchanged",true);
            report.addProperty("observer_check_scope","before fixture cleanup and ordinary server shutdown saves");
            report.addProperty("observer_file_sha256",sha(observerBefore));
            report.addProperty("viabackwards_sha256",sha(read(Path.of("mods/ViaBackwards-5.12.0.jar"))));
            report.addProperty("viafabric_sha256",sha(read(Path.of("mods/ViaFabric-0.4.22+186-26.x.jar"))));
            try(var jar=new java.util.zip.ZipFile("mods/ViaFabric-0.4.22+186-26.x.jar");var input=jar.getInputStream(jar.getEntry("META-INF/jars/viaversion-5.12.1-SNAPSHOT.jar"))) {
                report.addProperty("viaversion_nested_sha256",sha(input.readAllBytes()));
            }catch(Exception error){throw new IllegalStateException("Cannot bind executed ViaVersion bytes",error);}
            write(required("infinity.creative.persistence.report"),report.toString()+"\n");succeed();
        }

        @Override void cleanup(){if(viaChannel!=null)viaChannel.finishAndReleaseAll();}
    }

    private static final class Snapshot {
        final List<ItemStack> inventory=new ArrayList<>();
        final EnumMap<EquipmentSlot,ItemStack> equipment=new EnumMap<>(EquipmentSlot.class);
        final List<ItemStack> backpack;
        final CompoundTag modes;
        final int selected;
        Snapshot(ServerPlayer player) {
            for(int i=0;i<player.getInventory().getContainerSize();i++)inventory.add(player.getInventory().getItem(i).copy());
            for(var slot:EquipmentSlot.values())equipment.put(slot,player.getItemBySlot(slot).copy());
            backpack=BackpackStorage.read(player,"CREATIVE").stream().map(ItemStack::copy).toList();
            modes=GameModes.state(player).copy();selected=player.getInventory().getSelectedSlot();
        }
        void assertMatches(GameTestHelper c,ServerPlayer actual,String stage) {
            c.assertTrue(actual.isCreative()&&GameModes.current(actual)==GameModes.Mode.CREATIVE&&GameModes.of(actual.level())==GameModes.Mode.CREATIVE,"Actual Creative world/mode remains coherent after "+stage);
            c.assertValueEqual(actual.getInventory().getContainerSize(),inventory.size(),"Native inventory capacity persists after "+stage);
            for(int i=0;i<inventory.size();i++)c.assertTrue(ItemStack.matches(actual.getInventory().getItem(i),inventory.get(i)),"Every slot/count/component persists after "+stage+": "+i);
            for(var entry:equipment.entrySet())c.assertTrue(ItemStack.matches(actual.getItemBySlot(entry.getKey()),entry.getValue()),"Worn/offhand equipment retains every component after "+stage+": "+entry.getKey());
            c.assertValueEqual(actual.getInventory().getSelectedSlot(),selected,"Selected native hotbar slot persists after "+stage);
            c.assertValueEqual(GameModes.state(actual),modes,"Exact saved mode/backpack state persists after "+stage);
            c.assertTrue(actual.containerMenu==actual.inventoryMenu&&actual.containerMenu.getCarried().isEmpty(),"No cursor/open-container stack leaks across "+stage);
            var contents=BackpackStorage.read(actual,"CREATIVE");
            for(int i=0;i<backpack.size();i++)c.assertTrue(ItemStack.matches(contents.get(i),backpack.get(i)),"Personal backpack sparse slot/components persist after "+stage+": "+i);
            var weapon=actual.getInventory().getItem(6);
            c.assertTrue(weapon.is(Convergence.ITEMS.get("convergence:sword"))&&weapon.getDamageValue()==7,"Renamed canonical weapon damage survives "+stage);
            c.assertValueEqual(weapon.getHoverName().getString(),"Odyssey • Saved blade","Player custom name survives "+stage);
            var styles=List.of(actual.getInventory().getItem(34),actual.getInventory().getItem(35),actual.getItemBySlot(EquipmentSlot.CHEST));
            for(int i=0;i<styles.size();i++) {
                var style=BackpackStorage.STYLES.get(i);var stack=styles.get(i);
                c.assertTrue(stack.is(BackpackStorage.ITEM),"Every worn/inventory backpack remains canonical after "+stage);
                c.assertValueEqual(stack.get(DataComponents.DYED_COLOR).rgb(),style.color(),"Distinct backpack color persists after "+stage);
                c.assertValueEqual(stack.getHoverName().getString(),style.name(),"Distinct backpack name persists after "+stage);
            }
            c.assertTrue(contents.getFirst().is(Convergence.ITEMS.get("convergence:mace"))&&contents.getFirst().getDamageValue()==9,"Personal backpack retains its damaged canonical relic after "+stage);
            c.assertValueEqual(contents.getFirst().getHoverName().getString(),"Odyssey • Backpack relic","Personal backpack retains player custom name after "+stage);
        }
    }

    private static Path required(String property) {
        var value=System.getProperty(property);if(value==null)throw new IllegalStateException("Required actual proof path missing: -D"+property);return Path.of(value);
    }
    private static byte[] read(Path path){try{return Files.readAllBytes(path);}catch(Exception error){throw new IllegalStateException("Cannot read required actual proof: "+path,error);}}
    private static CompoundTag readNbt(Path path){try{return NbtIo.readCompressed(path,NbtAccounter.create(64L*1024*1024));}catch(Exception error){throw new IllegalStateException("Cannot read actual native player file: "+path,error);}}
    private static void write(Path path,String value){try{Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,value);}catch(Exception error){throw new IllegalStateException("Cannot write actual persistence proof",error);}}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception error){throw new IllegalStateException(error);}}
    private static JsonObject parse(byte[] bytes){return JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
    private static TreeMap<String,JsonObject> byPath(GameTestHelper c,JsonObject catalogue) {
        var entries=new TreeMap<String,JsonObject>();
        for(var value:catalogue.getAsJsonArray("items")){var entry=value.getAsJsonObject();String path=entry.get("path").getAsString();c.assertTrue(entries.putIfAbsent(path,entry)==null,"Catalogue rejects duplicate canonical paths");}
        return entries;
    }
}
