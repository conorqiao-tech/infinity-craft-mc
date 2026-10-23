package dev.infinityhub.proof;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SyncEntityPropertyPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.platform.standalone.GeyserStandaloneBootstrap;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.mappings.BuiltInMappings;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.RegistryCache;
import org.geysermc.geyser.session.cache.registry.JavaRegistries;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;

/** Offline protocol fixture. No network listener, auth flow, game world, asset fetch or renderer. */
public final class WingedChestProof {
    static final String PROPERTY = "infinityhub:winged_chest";
    static int cases;

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Expected isolated working directory and output receipt");
        Path work = Path.of(args[0]); var bootstrap = new OfflineBootstrap(work);
        org.geysermc.geyser.text.GeyserLocale.init(bootstrap);
        GeyserImpl geyser = GeyserImpl.load(bootstrap);
        geyser.eventBus().subscribe(geyser, GeyserDefineCustomBlocksEvent.class, BuiltInMappings::registerBlocks);
        geyser.eventBus().subscribe(geyser, GeyserDefineCustomItemsEvent.class, BuiltInMappings::registerItems);
        Registries.load(); BlockRegistries.populate(); Registries.populate(); RegistryCache.init();
        VanillaEntities.init(); MessageTranslator.init(); Registries.RESOURCE_PACKS.load(); Registries.WAYPOINT_STYLE_MAPPINGS.load();
        geyser.eventBus().fire(new GeyserPostInitializeEvent(geyser.extensionManager(), geyser.eventBus()));
        require(!Files.exists(work.resolve("extensions/infinitycreativebridge/failed.json")), "Actual extension failed startup");
        var schemas = Registries.BEDROCK_ENTITY_PROPERTIES.get().stream().filter(n -> n.getString("type").equals("minecraft:player")).toList();
        require(schemas.size() == 1, "Actual player schema registered exactly once");
        var schema = schemas.getFirst(); int propertyIndex = -1;
        var definitions = schema.getList("properties", NbtType.COMPOUND);
        for (int i = 0; i < definitions.size(); i++) if (definitions.get(i).getString("name").equals(PROPERTY)) {
            require(propertyIndex == -1 && definitions.get(i).getInt("type") == 2, "Property is a unique Boolean"); propertyIndex = i;
        }
        require(propertyIndex >= 0, "Actual registered property is missing");
        var observed = new com.google.gson.JsonArray();
        for (int protocol : List.of(1001, 2168, 2193)) {
            try (var s = new CaptureSession(Registries.ITEMS.get(protocol), protocol)) {
                var self = s.getPlayerEntity(); self.setEntityId(71); self.uuid(UUID.randomUUID());
                require(s.getEntityCache().getEntityByJavaId(71) == self, "Native self lookup returns SessionPlayerEntity without entity-map insertion");
                var sync = new SyncEntityPropertyPacket(); sync.setData(schema);
                require(((SyncEntityPropertyPacket)wire(s, sync)).getData().equals(schema), "The registered schema survives the real client codec"); cases++;
                s.upstreamPackets.clear(); self.updateBedrockEntityProperties();
                assertProperty(s, self, propertyIndex, false, "fresh self default");
                ItemStack wings = canonical(s, "minecraft:elytra", "convergence:chestplate");
                ItemStack plain = new ItemStack(s.getItemMappings().getMapping("minecraft:elytra").getJavaItem().javaId(), 1);
                var savedWing = wings.getDataComponentsPatch().clone();
                dispatch(s, new ClientboundContainerSetSlotPacket(0, 1, 6, wings));
                assertProperty(s, self, propertyIndex, true, "native self chest slot");
                require(s.getPlayerInventory().getItem(6).getItemStack().getDataComponentsPatch().equals(savedWing), "Winged adapter cannot mutate canonical cached components");
                require(s.getPlayerInventory().getItem(6).getItemData(s).getDefinition().getIdentifier().equals("minecraft:elytra"), "The wearable remains actual native elytra");
                dispatch(s, new ClientboundSetPlayerInventoryPacket(6, plain));
                assertPropertyUnchanged(s, self, propertyIndex, true, "raw player-inventory slot6 is native hotbar6, not chest");
                require(s.getPlayerInventory().getItem(42).is(org.geysermc.geyser.item.Items.ELYTRA)
                    && s.getPlayerInventory().getItem(6).getItemStack().getDataComponentsPatch().equals(savedWing), "Original player-inventory translator numbering is preserved");
                dispatch(s, new ClientboundContainerSetSlotPacket(0, 2, 6, plain));
                assertProperty(s, self, propertyIndex, false, "ordinary self elytra");
                for (ItemStack wrong : List.of(canonical(s,"minecraft:elytra","convergence:cuirass"),
                        canonical(s,"minecraft:netherite_chestplate","convergence:chestplate"),
                        new ItemStack(plain.getId(), 0, wings.getDataComponentsPatch().clone()),
                        malformed(s))) {
                    dispatch(s, new ClientboundContainerSetSlotPacket(0, 3, 6, wings)); assertProperty(s,self,propertyIndex,true,"before false marker");
                    dispatch(s, new ClientboundContainerSetSlotPacket(0, 4, 6, wrong)); assertProperty(s,self,propertyIndex,false,"wrong base/identity/shape rejected");
                }
                var inventory = new ItemStack[46]; inventory[6] = wings;
                dispatch(s, new ClientboundContainerSetContentPacket(0, 5, inventory, null));
                assertProperty(s,self,propertyIndex,true,"full inventory restores canonical self chest");
                var observer = new PlayerEntity(new EntitySpawnContext(s, VanillaEntities.PLAYER, 81, UUID.randomUUID()), "Observed player", Map.of());
                require(!observer.isValid(), "Observed player starts outside native tracking");
                s.getEntityCache().addPlayerEntity(observer); observer.sendPlayer();
                require(observer.isValid() && s.getEntityCache().getEntityByJavaId(81) == observer,
                    "The native player spawn lifecycle caches and makes the observer valid before equipment arrives");
                dispatch(s, new ClientboundSetEquipmentPacket(81, new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,wings)}));
                assertProperty(s,observer,propertyIndex,true,"observer native canonical equipment");
                var armor = s.upstreamPackets.stream().filter(MobArmorEquipmentPacket.class::isInstance).map(MobArmorEquipmentPacket.class::cast).findFirst().orElseThrow();
                require(((MobArmorEquipmentPacket)wire(s,armor)).getChestplate().getDefinition().getIdentifier().equals("minecraft:elytra"), "Observer retains native elytra armor identity");
                dispatch(s, new ClientboundSetEquipmentPacket(81, new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,plain)}));
                assertProperty(s,observer,propertyIndex,false,"observer ordinary elytra clears shell");
                dispatch(s, new ClientboundSetEquipmentPacket(81, new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,wings)}));
                assertProperty(s,observer,propertyIndex,true,"observer re-equip");
                dispatch(s, new ClientboundSetEquipmentPacket(81, new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,null)}));
                assertProperty(s,observer,propertyIndex,false,"observer removal clears shell");
                s.getEntityCache().removeEntity(observer);
                dispatch(s, new ClientboundSetEquipmentPacket(81, new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,wings)}));
                require(s.upstreamPackets.stream().noneMatch(SetEntityDataPacket.class::isInstance), "Removed observer cannot receive a stale update"); cases++;
                var replacement = new PlayerEntity(new EntitySpawnContext(s, VanillaEntities.PLAYER, 81, UUID.randomUUID()), "Replacement", Map.of());
                s.getEntityCache().addPlayerEntity(replacement); replacement.sendPlayer();
                require(replacement.isValid() && s.getEntityCache().getEntityByJavaId(81) == replacement, "Native tracking binds the new exact observer object");
                s.upstreamPackets.clear(); replacement.updateBedrockEntityProperties();
                assertProperty(s,replacement,propertyIndex,false,"replacement observer starts false");
                // Real cached inventory + installed request guard: ordinary move from hotbar to chest armor slot.
                inventory = new ItemStack[46]; inventory[36] = wings;
                dispatch(s,new ClientboundContainerSetContentPacket(0,6,inventory,null));
                assertProperty(s,self,propertyIndex,false,"self full clear");
                var source = new ItemStackRequestSlotData(ContainerSlotType.HOTBAR,0,s.getPlayerInventory().getItem(36).getNetId(),new FullContainerName(ContainerSlotType.HOTBAR,null));
                var target = new ItemStackRequestSlotData(ContainerSlotType.ARMOR,1,0,new FullContainerName(ContainerSlotType.ARMOR,null));
                var request = new ItemStackRequestPacket(); request.getRequests().add(new ItemStackRequest(1,new ItemStackRequestAction[]{new PlaceAction(1,source,target)},new String[0]));
                s.upstreamPackets.clear(); Registries.BEDROCK_PACKET_TRANSLATORS.translate(ItemStackRequestPacket.class,request,s,true); flush(s);
                var response = s.upstreamPackets.stream().filter(ItemStackResponsePacket.class::isInstance).map(ItemStackResponsePacket.class::cast).findFirst().orElseThrow();
                require(response.getEntries().getFirst().getResult() == ItemStackResponseStatus.OK, "Native request guard delegated actual armor equip successfully");
                assertProperty(s,self,propertyIndex,true,"installed guard after real local armor move");
                prepareDimension(s);
                var info = new PlayerSpawnInfo(0,Key.key("minecraft:overworld"),0,GameMode.CREATIVE,GameMode.CREATIVE,false,false,null,0,63);
                dispatch(s,new ClientboundRespawnPacket(info,false,false));
                assertProperty(s,self,propertyIndex,false,"native death respawn clears stale shell");
                dispatch(s,new ClientboundSetPlayerInventoryPacket(0,plain));
                assertPropertyUnchanged(s,self,propertyIndex,false,"unrelated inventory packet cannot revive predeath cached chest");
                inventory = new ItemStack[46]; inventory[6] = wings;
                dispatch(s,new ClientboundContainerSetContentPacket(0,7,inventory,null));
                assertProperty(s,self,propertyIndex,true,"authoritative restored inventory re-enables shell");
                dispatch(s,new ClientboundRespawnPacket(info,true,true));
                assertPropertyUnchanged(s,self,propertyIndex,true,"metadata-retaining dimension respawn preserves equipped wings");
                var item = new JsonObject();item.addProperty("protocol",protocol);item.addProperty("self_and_observer",true);observed.add(item);
            }
            try(var fresh = new CaptureSession(Registries.ITEMS.get(protocol),protocol)) {
                fresh.getPlayerEntity().setEntityId(71);fresh.upstreamPackets.clear();fresh.getPlayerEntity().updateBedrockEntityProperties();
                assertProperty(fresh,fresh.getPlayerEntity(),propertyIndex,false,"new session never inherits previous player's true marker");
                var savedInventory = new ItemStack[46]; savedInventory[6] = canonical(fresh,"minecraft:elytra","convergence:chestplate");
                dispatch(fresh,new ClientboundContainerSetContentPacket(0,1,savedInventory,null));
                assertProperty(fresh,fresh.getPlayerEntity(),propertyIndex,true,"initial saved wing inventory works on a fresh session without toggling gear");
            }
        }
        var result = new JsonObject(); result.addProperty("format",1);result.addProperty("cases",cases);result.add("observations",observed);
        result.addProperty("real_registered_java_paths",5);result.addProperty("installed_bedrock_request_guard",true);
        result.addProperty("schema_and_property_codec_roundtrips",true);result.addProperty("network_listener_started",false);
        result.addProperty("client_rendering_or_molang_executed",false);result.addProperty("live_world_access",false);
        Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
        System.out.println("WINGED_CHEST_PROOF_PASSED "+cases);System.exit(0);
    }

    private static ItemStack canonical(CaptureSession s,String base,String marker) {
        var data=new DataComponents(new HashMap<>());data.put(DataComponentTypes.CUSTOM_DATA,NbtMap.builder().putCompound("$polymer:stack",NbtMap.builder().putString("id",marker).putInt("count",1).build()).build());
        return new ItemStack(s.getItemMappings().getMapping(base).getJavaItem().javaId(),1,data);
    }
    private static ItemStack malformed(CaptureSession s) {
        var data=new DataComponents(new HashMap<>());data.put(DataComponentTypes.CUSTOM_DATA,NbtMap.builder().putString("$polymer:stack","not a compound").build());
        return new ItemStack(s.getItemMappings().getMapping("minecraft:elytra").getJavaItem().javaId(),1,data);
    }
    private static void prepareDimension(CaptureSession s) {
        var data=NbtMap.builder().putInt("min_y",-64).putInt("height",384).putString("skybox","overworld").build();
        s.getRegistryCache().load(new ClientboundRegistryDataPacket(Key.key("minecraft:dimension_type"),List.of(new RegistryEntry(Key.key("minecraft:overworld"),data))));
        s.setDimensionType(s.getRegistryCache().registry(JavaRegistries.DIMENSION_TYPE).byId(0));s.setWorldName(Key.key("minecraft:overworld"));
    }
    private static <P extends Packet> void dispatch(CaptureSession s,P packet) {
        s.upstreamPackets.clear();require(Registries.JAVA_PACKET_TRANSLATORS.translate(packet.getClass(),packet,s,true),"No registered Java translator for "+packet.getClass());flush(s);
    }
    private static void flush(CaptureSession s) {
        s.channel.runPendingTasks();s.getPlayerEntity().updateBedrockEntityProperties();
        for(var entity:s.getEntityCache().getEntitiesUnsafe().values())entity.updateBedrockEntityProperties();
    }
    private static void assertProperty(CaptureSession s,PlayerEntity entity,int index,boolean expected,String description)throws Exception {
        var values=new ArrayList<Integer>();
        for(var p:s.upstreamPackets)if(p instanceof SetEntityDataPacket d&&d.getRuntimeEntityId()==entity.geyserId()){
            var decoded=(SetEntityDataPacket)wire(s,d);
            for(var property:decoded.getProperties().getIntProperties())if(property.getIndex()==index)values.add(property.getValue());
        }
        require(!values.isEmpty()&&values.getLast()==(expected?1:0),description+" expected="+expected+" actual="+values);cases++;
    }
    private static void assertPropertyUnchanged(CaptureSession s,PlayerEntity entity,int index,boolean expected,String description)throws Exception {
        for(var p:s.upstreamPackets)if(p instanceof SetEntityDataPacket d&&d.getRuntimeEntityId()==entity.geyserId()){
            var decoded=(SetEntityDataPacket)wire(s,d);
            for(var property:decoded.getProperties().getIntProperties())if(property.getIndex()==index)
                require(property.getValue()==(expected?1:0),description+" emitted a wrong changed value");
        }
        cases++;
    }
    private static BedrockPacket wire(CaptureSession s,BedrockPacket packet)throws Exception {
        ByteBuf b=Unpooled.buffer();try{
            var codec=s.getUpstream().getSession().getCodec();codec.tryEncode(s.getUpstream().getCodecHelper(),b,packet);
            int id=s.clientCodec.getPacketDefinition(packet.getClass()).getId();var decoded=s.clientCodec.tryDecode(s.clientHelper,b,id,PacketRecipient.CLIENT);
            require(!b.isReadable()&&decoded.getClass()==packet.getClass(),"Exact native client codec consumes "+packet.getClass());return decoded;
        }finally{b.release();}
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static final class OfflineBootstrap extends GeyserStandaloneBootstrap {
        final Path work;final GeyserConfig configuration;
        OfflineBootstrap(Path work){this.work=work;configuration=super.loadConfig(GeyserConfig.class);}
        @Override public Path getConfigFolder(){return work;}
        @Override public GeyserConfig config(){return configuration;}
    }
    private static final class CaptureSession extends GeyserSession implements AutoCloseable {
        final List<BedrockPacket> upstreamPackets=new ArrayList<>();final List<Packet> downstreamPackets=new ArrayList<>();
        final EmbeddedChannel channel;final BedrockCodec clientCodec;final BedrockCodecHelper clientHelper;
        CaptureSession(ItemMappings mappings,int protocol){this(mappings,transport(protocol));}
        CaptureSession(ItemMappings mappings,Transport t){
            super(GeyserImpl.getInstance(),t.session(),t.channel().eventLoop());channel=t.channel();setItemMappings(mappings);setBlockMappings(BlockRegistries.BLOCKS.get(protocolVersion()));
            getUpstream().getCodecHelper().setItemDefinitions(mappings);getUpstream().getCodecHelper().setBlockDefinitions(getBlockMappings());
            clientCodec=switch(protocolVersion()){case 1001->Bedrock_v1001.CODEC;case 2168->Bedrock_v2168_hotfix4.CODEC;case 2193->Bedrock_v2193.CODEC;default->throw new IllegalStateException();};
            clientHelper=clientCodec.createHelper();clientHelper.setItemDefinitions(mappings);clientHelper.setBlockDefinitions(getBlockMappings());clientHelper.setEncodingSettings(EncodingSettings.CLIENT);setInventoryHolder(getPlayerInventoryHolder());
        }
        @Override public GameMode getGameMode(){return GameMode.CREATIVE;}
        @Override public void sendDownstreamGamePacket(Packet packet){downstreamPackets.add(packet);}
        @Override public void sendUpstreamPacket(BedrockPacket packet){upstreamPackets.add(packet);}
        @Override public void sendUpstreamPacketImmediately(BedrockPacket packet){upstreamPackets.add(packet);}
        @Override public void close(){channel.finishAndReleaseAll();}
        static Transport transport(int protocol){var c=new EmbeddedChannel(new BedrockPacketCodec_v3());var peer=new BedrockPeer(c,BedrockServerSession::new);var s=new BedrockServerSession(peer,0);s.setCodec(GameProtocol.getBedrockCodec(protocol));return new Transport(c,s);}
        record Transport(EmbeddedChannel channel,BedrockServerSession session){}
    }
}
