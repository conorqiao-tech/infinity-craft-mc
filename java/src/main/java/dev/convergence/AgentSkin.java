package dev.convergence;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Public, signed Minecraft texture data. No skin service credentials or runtime API calls. */
final class AgentSkin {
    private static final Property TEXTURE=load();
    private AgentSkin() {}
    static GameProfile profile(UUID id,String name) {
        return new GameProfile(id,name,new PropertyMap(ImmutableMultimap.of("textures",TEXTURE)));
    }
    private static Property load() {
        try(var stream=AgentSkin.class.getResourceAsStream("/assets/convergence/helpers/codex-chatgpt.json")) {
            if(stream==null)throw new IllegalStateException("Helper skin data is missing");
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            String value=json.get("value").getAsString(),signature=json.get("signature").getAsString();
            var payload=JsonParser.parseString(new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8)).getAsJsonObject();
            String url=payload.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            // Java clients only accept Minecraft's trusted texture host. Keep the signed payload intact.
            if(!url.matches("https?://textures\\.minecraft\\.net/texture/[a-f0-9]{64}")
                ||Base64.getDecoder().decode(signature).length<256)
                throw new IllegalStateException("Helper skin must contain signed Minecraft texture data");
            return new Property("textures",value,signature);
        } catch(java.io.IOException|IllegalArgumentException error) {
            throw new IllegalStateException("Cannot load helper skin",error);
        }
    }
}
