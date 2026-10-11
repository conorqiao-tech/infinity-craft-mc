package dev.convergence;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.LinkedHashMap;

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
                result.put(entry.getKey(),entry.getValue().getAsString());
            return Map.copyOf(result);
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot load Infinity item names",error);}
    }
    static String label(String path) {
        var name=NAMES.get("item.convergence."+path);
        if(name==null)name=NAMES.get("block.convergence."+path);
        if(name==null)throw new IllegalArgumentException("Missing Infinity name: "+path);
        return name;
    }
    static Component text(String path) {return Component.literal(label(path));}

    /** Resolve only our item/block keys; saved custom names can override ITEM_NAME. */
    static Component readableName(Component original) {
        MutableComponent result;
        if(original.getContents() instanceof TranslatableContents translated) {
            String key=translated.getKey();
            String name=NAMES.get(key);
            if(name!=null) result=Component.literal(name);
            else {
                Object[] arguments=translated.getArgs().clone();
                for(int i=0;i<arguments.length;i++)if(arguments[i] instanceof Component child)arguments[i]=readableName(child);
                result=MutableComponent.create(new TranslatableContents(key,translated.getFallback(),arguments));
            }
        } else result=original.plainCopy();
        result.setStyle(original.getStyle());
        for(var child:original.getSiblings())result.append(readableName(child));
        return result;
    }
}
