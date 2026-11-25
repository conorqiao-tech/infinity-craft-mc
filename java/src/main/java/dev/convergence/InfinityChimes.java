package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class InfinityChimes {
    static final String KEY = "infinity_chimes_score_v1";
    static final String BLOCK_TEXTURE = "textures/blocks/noteblock";
    private static final int CAPACITY = 8;
    private static final int BEAT_TICKS = 8;
    private static final List<String> NAMES = List.of("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B");
    private record Sample(BlockPos pos, int pitch) {}
    private record Playback(ServerGamePacketListenerImpl connection, ServerLevel world, int start, List<Integer> notes) {}
    private static final Map<ServerPlayer, Playback> PLAYING = new ConcurrentHashMap<>();

    private InfinityChimes() {}

    // Missing is a blank score. An unreadable score stays available for host recovery.
    static List<Integer> score(ServerPlayer p) {
        var tag = GameModes.state(p).get(KEY);
        if (tag == null) return List.of();
        if (!(tag instanceof IntArrayTag pitches) || pitches.size() > CAPACITY) return null;
        var notes = new ArrayList<Integer>(pitches.size());
        for (int pitch : pitches.getAsIntArray()) {
            if (pitch < 0 || pitch > 24) return null;
            notes.add(pitch);
        }
        return List.copyOf(notes);
    }

    private static String name(int pitch) {
        int midi = 54 + pitch; // Minecraft note block 0 is F#3.
        return NAMES.get(midi % 12) + (midi / 12 - 1);
    }

    private static Sample aimedNote(ServerPlayer p) {
        var hit = p.pick(Math.min(5, p.blockInteractionRange()), 1, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            var state = p.level().getBlockState(block.getBlockPos());
            if (state.is(Blocks.NOTE_BLOCK)) return new Sample(block.getBlockPos(), state.getValue(NoteBlock.NOTE));
        }
        return null;
    }

    private static void sound(ServerPlayer p, int pitch) {
        p.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
            p.getX(), p.getY(), p.getZ(), 1f, NoteBlock.getPitchFromNote(pitch), p.getRandom().nextLong()));
    }

    private static void save(ServerPlayer p, List<Integer> notes) {
        PLAYING.remove(p);
        var root = GameModes.state(p);
        if (notes.isEmpty()) root.remove(KEY);
        else root.putIntArray(KEY, notes.stream().mapToInt(Integer::intValue).toArray());
        PlayerPersistence.save(p);
    }

    private static void play(ServerPlayer p, List<Integer> notes) {
        PLAYING.remove(p);
        sound(p, notes.getFirst());
        if (notes.size() > 1) PLAYING.put(p, new Playback(p.connection, p.level(), p.level().getServer().getTickCount(), notes));
    }

    static void tick(MinecraftServer server) {
        if (PLAYING.isEmpty()) return;
        PLAYING.entrySet().removeIf(entry -> {
            var p = entry.getKey();
            var tune = entry.getValue();
            if (tune.world().getServer() != server) return false;
            if (p.level() != tune.world() || p.connection != tune.connection() || !ServerMenu.allowed(p)) return true;
            int elapsed = server.getTickCount() - tune.start();
            if (elapsed <= 0 || elapsed % BEAT_TICKS != 0) return false;
            int index = elapsed / BEAT_TICKS;
            if (index < tune.notes().size()) sound(p, tune.notes().get(index));
            return index >= tune.notes().size() - 1;
        });
    }

    static void disconnected(ServerGamePacketListenerImpl handler) {
        var tune = PLAYING.get(handler.player);
        if (tune != null && tune.connection() == handler) PLAYING.remove(handler.player);
    }

    static void stopped(MinecraftServer server) {
        PLAYING.values().removeIf(tune -> tune.world().getServer() == server);
    }

    private static InfinityJourney.Choice action(ServerPlayer p, List<Integer> expected, Item icon,
            String label, String texture, Runnable change) {
        return new InfinityJourney.Choice(icon, label, texture, () -> {
            if (!InfinityJourney.emptyScreen(p)) return;
            if (expected.equals(score(p))) change.run();
            else CommunityServer.say(p, "Your score changed. Review the current notes.");
            InfinityJourney.open(p, InfinityJourney.Page.CHIMES);
        });
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var notes = score(p);
        var choices = new ArrayList<InfinityJourney.Choice>();
        var body = new StringBuilder();
        if (notes == null) body.append("Your saved score needs host review.");
        else {
            body.append("Aim at a tuned note block and copy a note. Names use the standard harp octave. Play your score anywhere in a private chime voice.\n\n")
                .append(notes.size()).append("/").append(CAPACITY).append(" notes");
            if (!notes.isEmpty()) body.append("\n").append(notes.stream().map(InfinityChimes::name).collect(Collectors.joining(" • ")));
            var sample = aimedNote(p);
            if (notes.size() == CAPACITY) body.append("\nScore full. Undo the last note to make room.");
            else if (sample == null) body.append("\nClose the menu and aim at a note block within reach to add a note.");
            else choices.add(action(p, notes, Items.NOTE_BLOCK, "Copy " + name(sample.pitch()), BLOCK_TEXTURE, () -> {
                if (!sample.equals(aimedNote(p))) {
                    CommunityServer.say(p, "The target or pitch changed. Aim and review again.");
                    return;
                }
                var extended = new ArrayList<>(notes);
                extended.add(sample.pitch());
                save(p, extended);
                sound(p, sample.pitch());
            }));
            if (!notes.isEmpty()) {
                choices.add(action(p, notes, Items.AMETHYST_SHARD, "Play my score", "textures/items/amethyst_shard", () -> play(p, notes)));
                choices.add(action(p, notes, Items.FEATHER, "Undo last note • " + name(notes.getLast()), null,
                    () -> save(p, notes.subList(0, notes.size() - 1))));
            }
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(p)));
        return new InfinityJourney.View("Infinity Chimes", body.toString(), List.copyOf(choices));
    }
}
