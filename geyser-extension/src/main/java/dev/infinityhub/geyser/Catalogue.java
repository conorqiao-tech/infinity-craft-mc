package dev.infinityhub.geyser;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The server exports public definitions only; no arbitrary NBT, commands or raw registry IDs are accepted. */
final class Catalogue {
    static final int MAX_BYTES = 131_072;
    static final int MAX_ITEMS = 128;
    static final String ALIAS_NAMESPACE = "infinity_catalog";
    private static final Set<String> ROOT_KEYS = Set.of("format_version", "minecraft", "items");
    private static final Set<String> ITEM_KEYS = Set.of("path", "base", "name", "icon", "max_stack_size", "category",
            "max_damage", "slot", "protection", "handheld", "native_appearance");
    final List<Entry> items;
    final String sha256;

    private Catalogue(List<Entry> items, String sha256) { this.items = List.copyOf(items); this.sha256 = sha256; }

    static Catalogue read(Path path) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) < 1 || Files.size(path) > MAX_BYTES)
            throw new IOException("Missing or oversized Infinity catalogue: " + path.getFileName());
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Infinity catalogue exceeded bounded read");
        String json = new String(bytes, StandardCharsets.UTF_8);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            rejectDuplicateKeys(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing catalogue data");
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Catalogue must be an object");
        JsonObject root = parsed.getAsJsonObject(); keys(root, ROOT_KEYS);
        if (integer(root, "format_version", 1, 1) != 1 || !text(root, "minecraft", 16).equals("26.4-snapshot-5"))
            throw new IllegalArgumentException("Unsupported catalogue format or Minecraft version");
        if (!root.has("items") || !root.get("items").isJsonArray()) throw new IllegalArgumentException("Missing item list");
        JsonArray array = root.getAsJsonArray("items");
        if (array.isEmpty() || array.size() > MAX_ITEMS) throw new IllegalArgumentException("Catalogue item count out of bounds");
        List<Entry> items = new ArrayList<>(); Set<String> paths = new HashSet<>();
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("Invalid catalogue item");
            JsonObject item = value.getAsJsonObject(); keys(item, ITEM_KEYS);
            String id = text(item, "path", 64);
            if (!id.matches("[a-z0-9_]+") || !paths.add(id)) throw new IllegalArgumentException("Invalid/duplicate catalogue path");
            String base = text(item, "base", 96);
            if (!base.matches("minecraft:[a-z0-9_]+") || base.equals("minecraft:air")) throw new IllegalArgumentException("Invalid vanilla base for " + id);
            String name = text(item, "name", 128);
            if (name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7)) throw new IllegalArgumentException("Invalid literal name for " + id);
            String icon = text(item, "icon", 128);
            if (!icon.matches("[a-z0-9_./:]+")) throw new IllegalArgumentException("Invalid icon for " + id);
            String category = text(item, "category", 24);
            if (!Set.of("equipment", "construction", "items", "nature").contains(category)) throw new IllegalArgumentException("Invalid Creative category");
            int stack = integer(item, "max_stack_size", 1, 64);
            Integer damage = item.has("max_damage") ? integer(item, "max_damage", 1, Integer.MAX_VALUE) : null;
            if (damage != null && stack != 1) throw new IllegalArgumentException("Damageable item must not stack");
            String slot = item.has("slot") ? text(item, "slot", 16) : null;
            if (slot != null && !Set.of("head", "chest", "legs", "feet", "body", "offhand", "mainhand").contains(slot))
                throw new IllegalArgumentException("Invalid equipment slot");
            items.add(new Entry(id, base, name, icon, stack, category, damage, slot,
                    integer(item, "protection", 0, 30), bool(item, "handheld"), bool(item, "native_appearance")));
        }
        return new Catalogue(items, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static void keys(JsonObject object, Set<String> allowed) {
        if (!allowed.containsAll(object.keySet())) throw new IllegalArgumentException("Unsupported catalogue field");
    }
    private static void rejectDuplicateKeys(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw new IllegalArgumentException("Catalogue nesting exceeded limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); Set<String> keys = new HashSet<>();
                while (reader.hasNext()) {
                    if (!keys.add(reader.nextName()) || keys.size() > 32) throw new IllegalArgumentException("Duplicate or excessive catalogue keys");
                    rejectDuplicateKeys(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); int count = 0;
                while (reader.hasNext()) {
                    if (++count > 512) throw new IllegalArgumentException("Catalogue array exceeded limit");
                    rejectDuplicateKeys(reader, depth + 1);
                }
                reader.endArray();
            }
            case STRING, NUMBER, BOOLEAN, NULL -> reader.skipValue();
            default -> throw new IllegalArgumentException("Invalid catalogue JSON token");
        }
    }
    private static String text(JsonObject object, String key, int max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string: " + key);
        String text = value.getAsString();
        if (text.isBlank() || text.length() > max) throw new IllegalArgumentException("String length out of bounds: " + key);
        return text;
    }
    private static int integer(JsonObject object, String key, int min, int max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer: " + key);
        int result;
        try { result = value.getAsBigDecimal().intValueExact(); } catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid integer: " + key); }
        if (result < min || result > max) throw new IllegalArgumentException("Integer out of bounds: " + key);
        return result;
    }
    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean: " + key);
        return value.getAsBoolean();
    }
    record Entry(String path, String base, String name, String icon, int maxStack, String category,
            Integer maxDamage, String slot, int protection, boolean handheld, boolean nativeAppearance) {
        String alias() { return ALIAS_NAMESPACE + ":" + path; }
        String canonical() { return "convergence:" + path; }
    }
}
