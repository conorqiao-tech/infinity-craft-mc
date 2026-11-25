package dev.convergence;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** A player's single Atlas bookmark. Navigation only reads block coordinates. */
record AtlasTrailhead(String dimension, BlockPos pos) {
    static final String KEY = "infinity_atlas_trailhead";
    private static final Set<String> FIELDS = Set.of("format", "dimension", "x", "y", "z");
    private static final List<String> DIRECTIONS = List.of("north", "northeast", "east", "southeast",
        "south", "southwest", "west", "northwest");

    static AtlasTrailhead read(ServerPlayer p) {
        var tag = GameModes.state(p).getCompound(KEY).orElse(null);
        if (tag == null || !tag.keySet().equals(FIELDS) || !(tag.get("format") instanceof IntTag)
                || tag.getIntOr("format", -1) != 1 || !(tag.get("x") instanceof IntTag)
                || !(tag.get("y") instanceof IntTag) || !(tag.get("z") instanceof IntTag)) return null;
        String dimension = tag.getStringOr("dimension", "");
        Identifier id = Identifier.tryParse(dimension);
        if (id == null || id.getPath().isEmpty() || !id.toString().equals(dimension)) return null;
        var pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
        if (!Level.isInSpawnableBounds(pos) || pos.getY() < DimensionType.MIN_Y || pos.getY() > DimensionType.MAX_Y)
            return null;
        return new AtlasTrailhead(dimension, pos);
    }

    static int mark(ServerPlayer p, AtlasTrailhead expected) {
        var root = GameModes.state(p);
        var here = p.blockPosition();
        if (p.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || !CommonsEvents.harvestAllowed(p)
                || !p.level().isInWorldBounds(here) || !p.level().getWorldBorder().isWithinBounds(here)
                || root.contains(KEY) != (expected != null) || !Objects.equals(read(p), expected)) return 0;
        var tag = new CompoundTag();
        tag.putInt("format", 1);
        tag.putString("dimension", p.level().dimension().identifier().toString());
        tag.putInt("x", here.getX());
        tag.putInt("y", here.getY());
        tag.putInt("z", here.getZ());
        root.put(KEY, tag);
        PlayerPersistence.save(p);
        return 1;
    }

    String navigation(ServerPlayer p) {
        String location = "Trailhead at " + pos.toShortString() + " in " + dimension + ". ";
        if (!dimension.equals(p.level().dimension().identifier().toString()))
            return location + "Return to that dimension for a bearing.";
        BlockPos here = p.blockPosition();
        double dx = (double) pos.getX() - here.getX(), dz = (double) pos.getZ() - here.getZ();
        if (dx == 0 && dz == 0) return location + "You are at its X/Z coordinates.";
        int sector = Math.floorMod((int) Math.round(Math.atan2(dx, -dz) / (Math.PI / 4)), 8);
        return location + "Head " + DIRECTIONS.get(sector) + " • about " + Math.round(Math.hypot(dx, dz)) + " blocks horizontally.";
    }
}
