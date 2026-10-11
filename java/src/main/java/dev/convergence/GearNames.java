package dev.convergence;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.text.Text;

/** Literal names also work when Geyser or a Java client has no custom language file. */
final class GearNames {
    private static final Map<String,String> NAMES=load();
    private GearNames() {}
    private static Map<String,String> load() {
        try(var stream=GearNames.class.getResourceAsStream("/assets/convergence/lang/en_us.json")) {
            if(stream==null)throw new IllegalStateException("Infinity item names are missing");
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var result=new LinkedHashMap<String,String>();
            for(var entry:json.entrySet())if(entry.getKey().startsWith("item.convergence.")||entry.getKey().startsWith("block.convergence."))
                result.put(entry.getKey().substring(entry.getKey().lastIndexOf('.')+1),entry.getValue().getAsString());
            return Map.copyOf(result);
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot load Infinity item names",error);}
    }
    static String label(String path) {
        var name=NAMES.get(path);
        if(name==null)throw new IllegalArgumentException("Missing Infinity name: "+path);
        return name;
    }
    static Text text(String path) {return Text.literal(label(path));}
}
