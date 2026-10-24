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
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
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
    static final String INFINITY_PROPERTY = "infinityhub:infinity_winged_chest";
    static final int EXPECTED_CASES = 192;
    static final List<String> CANONICAL_IDS = List.of("convergence:chestplate", "convergence:infinity_chestplate");
    static final java.util.LinkedHashSet<String> caseLabels = new java.util.LinkedHashSet<>();
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
        var schema = schemas.getFirst();
        int[] propertyIndices = {propertyIndex(schema, PROPERTY), propertyIndex(schema, INFINITY_PROPERTY)};
        require(propertyIndices[0] != propertyIndices[1], "The two Boolean properties have distinct schema indices");
        var observed = new com.google.gson.JsonArray();
        for (int protocol : List.of(1001, 2168, 2193)) {
            int beforeCases = cases;
            UUID identity = UUID.randomUUID();
            try (var s = new CaptureSession(Registries.ITEMS.get(protocol), protocol)) {
                var self = s.getPlayerEntity(); self.setEntityId(71); self.uuid(identity);
                require(s.getEntityCache().getEntityByJavaId(71) == self, "Native self lookup returns SessionPlayerEntity without entity-map insertion");
                var sync = new SyncEntityPropertyPacket(); sync.setData(schema);
                require(((SyncEntityPropertyPacket)wire(s, sync)).getData().equals(schema), "Both registered Boolean schemas survive the real client codec");
                recordCase(s, "schema codec");
                s.upstreamPackets.clear(); self.updateBedrockEntityProperties();
                assertPair(s, self, propertyIndices, false, false, "fresh self defaults");
                ItemStack plain = new ItemStack(s.getItemMappings().getMapping("minecraft:elytra").getJavaItem().javaId(), 1);
                ItemStack convergence = canonical(s,"minecraft:elytra",CANONICAL_IDS.get(0));
                ItemStack infinity = canonical(s,"minecraft:elytra",CANONICAL_IDS.get(1));
                for (int suit = 0; suit < CANONICAL_IDS.size(); suit++) {
                    boolean isInfinity = suit == 1;
                    String name = isInfinity ? "Infinity" : "Convergence";
                    ItemStack wings = isInfinity ? infinity : convergence;
                    ItemStack other = isInfinity ? convergence : infinity;
                    var saved = wings.getDataComponentsPatch().clone();
                    dispatch(s,new ClientboundContainerSetSlotPacket(0,1,6,wings));
                    assertPair(s,self,propertyIndices,true,isInfinity,name+" self chest");
                    assertCanonicalNative(s,s.getPlayerInventory().getItem(6),saved,name+" canonical self components and native flight item");
                    dispatch(s,new ClientboundSetPlayerInventoryPacket(6,other));
                    assertPairUnchanged(s,self,propertyIndices,true,isInfinity,name+" raw player slot6 is hotbar6");
                    require(s.getPlayerInventory().getItem(42).getItemStack().getDataComponentsPatch().equals(other.getDataComponentsPatch())
                        && s.getPlayerInventory().getItem(6).getItemStack().getDataComponentsPatch().equals(saved),
                        "The original player-inventory translator keeps native slot42 separate from chest6");
                    dispatch(s,new ClientboundContainerSetSlotPacket(0,2,6,plain));
                    assertPair(s,self,propertyIndices,false,false,name+" ordinary elytra clears both");
                    var invalid = List.of(canonical(s,"minecraft:elytra","convergence:cuirass"),
                        canonical(s,"minecraft:netherite_chestplate",CANONICAL_IDS.get(suit)),
                        new ItemStack(plain.getId(),0,wings.getDataComponentsPatch().clone()),malformed(s));
                    for (int i=0;i<invalid.size();i++) {
                        dispatch(s,new ClientboundContainerSetSlotPacket(0,3,6,wings));
                        assertPair(s,self,propertyIndices,true,isInfinity,name+" before invalid "+i);
                        dispatch(s,new ClientboundContainerSetSlotPacket(0,4,6,invalid.get(i)));
                        assertPair(s,self,propertyIndices,false,false,name+" rejects invalid "+i);
                    }
                    var inventory = new ItemStack[46]; inventory[6] = wings;
                    dispatch(s,new ClientboundContainerSetContentPacket(0,5,inventory,null));
                    assertPair(s,self,propertyIndices,true,isInfinity,name+" full authoritative chest");
                    assertCanonicalNative(s,s.getPlayerInventory().getItem(6),saved,name+" full contents preserve canonical components");
                    dispatch(s,new ClientboundContainerSetSlotPacket(0,6,6,null));
                    assertPair(s,self,propertyIndices,false,false,name+" explicit self unequip");
                }
                for (int step=0;step<4;step++) {
                    ItemStack item = step==0 || step==2 ? convergence : step==1 ? infinity : plain;
                    dispatch(s,new ClientboundContainerSetSlotPacket(0,7+step,6,item));
                    assertPair(s,self,propertyIndices,step<3,step==1,"direct self suit transition "+step);
                }
                var observer = new PlayerEntity(new EntitySpawnContext(s,VanillaEntities.PLAYER,81,UUID.randomUUID()),"Observed player",Map.of());
                require(!observer.isValid(),"Observed player starts outside native tracking");
                s.getEntityCache().addPlayerEntity(observer); observer.sendPlayer();
                require(observer.isValid()&&s.getEntityCache().getEntityByJavaId(81)==observer,"Native tracking establishes the exact observer object");
                observer.updateBedrockEntityProperties();
                assertPair(s,observer,propertyIndices,false,false,"fresh observer defaults");
                for (int suit=0;suit<CANONICAL_IDS.size();suit++) {
                    boolean isInfinity=suit==1;String name=isInfinity?"Infinity":"Convergence";
                    ItemStack wings=isInfinity?infinity:convergence;
                    dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,wings)}));
                    assertPair(s,observer,propertyIndices,true,isInfinity,name+" observer chest");
                    assertCanonicalNative(s,observer.getItemInSlot(EquipmentSlot.CHESTPLATE),wings.getDataComponentsPatch(),name+" observer canonical native elytra");
                    var armor=s.upstreamPackets.stream().filter(MobArmorEquipmentPacket.class::isInstance).map(MobArmorEquipmentPacket.class::cast).findFirst().orElseThrow();
                    require(((MobArmorEquipmentPacket)wire(s,armor)).getChestplate().getDefinition().getIdentifier().equals("minecraft:elytra"),"Observer wearable codec retains native elytra identity");
                    dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,plain)}));
                    assertPair(s,observer,propertyIndices,false,false,name+" observer plain clear");
                    dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,wings)}));
                    assertPair(s,observer,propertyIndices,true,isInfinity,name+" observer re-equip");
                    dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,null)}));
                    assertPair(s,observer,propertyIndices,false,false,name+" observer unequip");
                }
                for (int step=0;step<4;step++) {
                    ItemStack item=step==0||step==2?convergence:step==1?infinity:plain;
                    dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,item)}));
                    assertPair(s,observer,propertyIndices,step<3,step==1,"direct observer suit transition "+step);
                }
                s.getEntityCache().removeEntity(observer);
                dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,infinity)}));
                require(s.upstreamPackets.stream().noneMatch(SetEntityDataPacket.class::isInstance),"Removed observer receives no stale marker update");
                recordCase(s,"removed observer rejects stale update");
                var replacement=new PlayerEntity(new EntitySpawnContext(s,VanillaEntities.PLAYER,81,UUID.randomUUID()),"Replacement",Map.of());
                s.getEntityCache().addPlayerEntity(replacement);replacement.sendPlayer();
                require(replacement.isValid()&&s.getEntityCache().getEntityByJavaId(81)==replacement,"Replacement owns exact native tracking identity");
                replacement.updateBedrockEntityProperties();
                assertPair(s,replacement,propertyIndices,false,false,"replacement observer false defaults");
                dispatch(s,new ClientboundSetEquipmentPacket(81,new Equipment[]{new Equipment(EquipmentSlot.CHESTPLATE,infinity)}));
                assertPair(s,replacement,propertyIndices,true,true,"replacement observer authoritative Infinity chest");
                // Both real armor requests use the native open inventory before death closes it.
                for(int suit=0;suit<CANONICAL_IDS.size();suit++) {
                    boolean isInfinity=suit==1;String name=isInfinity?"Infinity":"Convergence";ItemStack wings=isInfinity?infinity:convergence;
                    var inventory=new ItemStack[46];inventory[36]=wings;
                    dispatch(s,new ClientboundContainerSetContentPacket(0,20+suit,inventory,null));
                    assertPairUnchanged(s,self,propertyIndices,false,false,name+" local move empty chest");
                    localArmorMove(s,50+suit);
                    assertPair(s,self,propertyIndices,true,isInfinity,name+" installed guard real armor move");
                    assertCanonicalNative(s,s.getPlayerInventory().getItem(6),wings.getDataComponentsPatch(),name+" local guard preserves canonical flight components");
                }
                prepareDimension(s);
                for(int suit=0;suit<CANONICAL_IDS.size();suit++) {
                    boolean isInfinity=suit==1;String name=isInfinity?"Infinity":"Convergence";ItemStack wings=isInfinity?infinity:convergence;
                    // Establish the real authoritative chest for this lifecycle independently of the last local request.
                    var inventory=new ItemStack[46];inventory[6]=wings;
                    dispatch(s,new ClientboundContainerSetContentPacket(0,25+suit,inventory,null));
                    consumePair(s,self,propertyIndices);require(pairEquals(s,self,true,isInfinity),"Death fixture begins with its own canonical suit");
                    String sourceWorld=suit==0?"minecraft:overworld":"minecraft:the_nether";
                    var info=new PlayerSpawnInfo(suit,Key.key(sourceWorld),0,GameMode.CREATIVE,GameMode.CREATIVE,false,false,null,0,63);
                    dispatch(s,new ClientboundRespawnPacket(info,false,false));
                    assertPair(s,self,propertyIndices,false,false,name+" native death clears both markers");
                    dispatch(s,new ClientboundSetPlayerInventoryPacket(0,plain));
                    assertPairUnchanged(s,self,propertyIndices,false,false,name+" unrelated inventory cannot revive predeath chest");
                    inventory=new ItemStack[46];inventory[6]=wings;
                    dispatch(s,new ClientboundContainerSetContentPacket(0,30+suit,inventory,null));
                    assertPair(s,self,propertyIndices,true,isInfinity,name+" authoritative postdeath restore");
                    int targetDimension=suit==0?1:0;String targetWorld=suit==0?"minecraft:the_nether":"minecraft:overworld";
                    var moved=new PlayerSpawnInfo(targetDimension,Key.key(targetWorld),0,GameMode.CREATIVE,GameMode.CREATIVE,false,false,null,0,63);
                    dispatch(s,new ClientboundRespawnPacket(moved,true,true));
                    assertPairUnchanged(s,self,propertyIndices,true,isInfinity,name+" actual metadata-retaining dimension switch");
                    require(s.getWorldName().equals(Key.key(targetWorld))&&s.getDimensionType()==s.getRegistryCache().registry(JavaRegistries.DIMENSION_TYPE).byId(targetDimension),
                        "The original translator actually changes the native world and dimension");
                    var change=s.upstreamPackets.stream().filter(ChangeDimensionPacket.class::isInstance).map(ChangeDimensionPacket.class::cast).reduce((first,last)->last).orElseThrow();
                    require(((ChangeDimensionPacket)wire(s,change)).getDimension()==targetDimension,"The actual dimension-change packet survives the pinned client codec");
                }
            }
            try(var fresh=new CaptureSession(Registries.ITEMS.get(protocol),protocol)) {
                var self=fresh.getPlayerEntity();self.setEntityId(71);self.uuid(identity);
                fresh.upstreamPackets.clear();self.updateBedrockEntityProperties();
                assertPair(fresh,self,propertyIndices,false,false,"same UUID fresh session false defaults");
                var inventory=new ItemStack[46];inventory[6]=canonical(fresh,"minecraft:elytra",CANONICAL_IDS.get(0));
                dispatch(fresh,new ClientboundContainerSetContentPacket(0,1,inventory,null));
                assertPair(fresh,self,propertyIndices,true,false,"reconnect initial saved Convergence chest");
                inventory=new ItemStack[46];dispatch(fresh,new ClientboundContainerSetContentPacket(0,2,inventory,null));
                assertPair(fresh,self,propertyIndices,false,false,"reconnect authoritative unequip");
                inventory[6]=canonical(fresh,"minecraft:elytra",CANONICAL_IDS.get(1));
                dispatch(fresh,new ClientboundContainerSetContentPacket(0,3,inventory,null));
                assertPair(fresh,self,propertyIndices,true,true,"reconnect initial saved Infinity chest");
            }
            require(cases-beforeCases==64,"Exactly64 meaningful cases required per native codec; actual="+(cases-beforeCases));
            var item=new JsonObject();item.addProperty("protocol",protocol);item.addProperty("cases",cases-beforeCases);
            item.addProperty("self_and_observer",true);item.addProperty("both_canonical_suits",true);
            item.addProperty("real_local_armor_moves_both_suits",true);item.addProperty("consistent_property_pairs",true);observed.add(item);
        }
        require(cases==EXPECTED_CASES&&caseLabels.size()==EXPECTED_CASES,"The exact complete case ledger is required");
        var result=new JsonObject();result.addProperty("format",2);result.addProperty("cases",cases);result.add("observations",observed);
        var labels=new com.google.gson.JsonArray();caseLabels.forEach(labels::add);result.add("case_labels",labels);
        var propertyNames=new com.google.gson.JsonArray();propertyNames.add(PROPERTY);propertyNames.add(INFINITY_PROPERTY);result.add("properties",propertyNames);
        var canonicalNames=new com.google.gson.JsonArray();CANONICAL_IDS.forEach(canonicalNames::add);result.add("canonical_item_ids",canonicalNames);
        result.addProperty("real_registered_java_paths",5);result.addProperty("installed_bedrock_request_guard",true);
        result.addProperty("schema_and_property_codec_roundtrips",true);result.addProperty("property_consistency_on_every_flushed_packet",true);
        result.addProperty("network_listener_started",false);result.addProperty("client_rendering_or_molang_executed",false);result.addProperty("live_world_access",false);
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
        var nether=NbtMap.builder().putInt("min_y",0).putInt("height",256).putString("skybox","nether").build();
        s.getRegistryCache().load(new ClientboundRegistryDataPacket(Key.key("minecraft:dimension_type"),List.of(
            new RegistryEntry(Key.key("minecraft:overworld"),data),new RegistryEntry(Key.key("minecraft:the_nether"),nether))));
        s.setDimensionType(s.getRegistryCache().registry(JavaRegistries.DIMENSION_TYPE).byId(0));s.setWorldName(Key.key("minecraft:overworld"));
    }
    private static <P extends Packet> void dispatch(CaptureSession s,P packet) {
        s.upstreamPackets.clear();require(Registries.JAVA_PACKET_TRANSLATORS.translate(packet.getClass(),packet,s,true),"No registered Java translator for "+packet.getClass());flush(s);
    }
    private static void flush(CaptureSession s) {
        s.channel.runPendingTasks();s.getPlayerEntity().updateBedrockEntityProperties();
        for(var entity:s.getEntityCache().getEntitiesUnsafe().values())entity.updateBedrockEntityProperties();
    }
    private static int propertyIndex(NbtMap schema,String name) {
        int index=-1;var definitions=schema.getList("properties",NbtType.COMPOUND);
        for(int i=0;i<definitions.size();i++)if(definitions.get(i).getString("name").equals(name)){
            require(index==-1&&definitions.get(i).getInt("type")==2,"Property is a unique Boolean: "+name);index=i;}
        require(index>=0,"Actual registered property is missing: "+name);return index;
    }
    private static void assertCanonicalNative(CaptureSession s,GeyserItemStack actual,DataComponents expected,String description) {
        require(!actual.isEmpty()&&actual.is(org.geysermc.geyser.item.Items.ELYTRA)
            &&actual.getItemStack().getDataComponentsPatch().equals(expected)
            &&actual.getItemData(s).getDefinition().getIdentifier().equals("minecraft:elytra"),description);
    }
    private static void localArmorMove(CaptureSession s,int requestId) {
        var source=new ItemStackRequestSlotData(ContainerSlotType.HOTBAR,0,s.getPlayerInventory().getItem(36).getNetId(),new FullContainerName(ContainerSlotType.HOTBAR,null));
        var target=new ItemStackRequestSlotData(ContainerSlotType.ARMOR,1,0,new FullContainerName(ContainerSlotType.ARMOR,null));
        var request=new ItemStackRequestPacket();request.getRequests().add(new ItemStackRequest(requestId,new ItemStackRequestAction[]{new PlaceAction(1,source,target)},new String[0]));
        s.upstreamPackets.clear();Registries.BEDROCK_PACKET_TRANSLATORS.translate(ItemStackRequestPacket.class,request,s,true);flush(s);
        var response=s.upstreamPackets.stream().filter(ItemStackResponsePacket.class::isInstance).map(ItemStackResponsePacket.class::cast).findFirst().orElseThrow();
        require(response.getEntries().getFirst().getResult()==ItemStackResponseStatus.OK,"The installed request guard delegates the actual native armor move");
    }
    private static int consumePair(CaptureSession s,PlayerEntity entity,int[] indices)throws Exception {
        int touched=0;int[] state=s.wireStates.computeIfAbsent(entity.geyserId(),unused->new int[]{-1,-1});
        for(var packet:s.upstreamPackets)if(packet instanceof SetEntityDataPacket data&&data.getRuntimeEntityId()==entity.geyserId()){
            var decoded=(SetEntityDataPacket)wire(s,data);boolean relevant=false;var packetIndices=new java.util.HashSet<Integer>();
            for(var property:decoded.getProperties().getIntProperties())for(int i=0;i<indices.length;i++)if(property.getIndex()==indices[i]){
                require(packetIndices.add(property.getIndex()),"Native metadata must not repeat a wing-property index in one flush");
                require(property.getValue()==0||property.getValue()==1,"Boolean property uses only native0/1");state[i]=property.getValue();relevant=true;touched++;}
            if(relevant){require(state[0]>=0&&state[1]>=0,"Both real wire defaults initialize the pair");
                require(state[1]!=1||state[0]==1,"A flushed native property packet published an inconsistent Infinity selector");}
        }
        return touched;
    }
    private static boolean pairEquals(CaptureSession s,PlayerEntity entity,boolean winged,boolean infinity){
        int[] value=s.wireStates.get(entity.geyserId());return value!=null&&value[0]==(winged?1:0)&&value[1]==(infinity?1:0);
    }
    private static void assertPair(CaptureSession s,PlayerEntity entity,int[] indices,boolean winged,boolean infinity,String description)throws Exception {
        require(consumePair(s,entity,indices)>0,description+" requires actual flushed native property values");
        require(pairEquals(s,entity,winged,infinity),description+" final real codec property pair differs");recordCase(s,description);
    }
    private static void assertPairUnchanged(CaptureSession s,PlayerEntity entity,int[] indices,boolean winged,boolean infinity,String description)throws Exception {
        consumePair(s,entity,indices);require(pairEquals(s,entity,winged,infinity),description+" changed the cached actual-wire pair");recordCase(s,description);
    }
    private static void recordCase(CaptureSession s,String description){
        require(caseLabels.add(s.protocolVersion()+" "+description),"Duplicate proof case label: "+description);cases++;
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
        final Map<Long,int[]> wireStates=new HashMap<>();
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
