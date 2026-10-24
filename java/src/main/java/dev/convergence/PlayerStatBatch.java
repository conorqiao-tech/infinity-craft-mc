package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Explicit online-player edits, fully reviewed before any native value changes.
 * The plan is connection-bound and single-use. Native player saving remains the
 * ordinary per-player save path; this is not a transaction across player files.
 */
final class PlayerStatBatch {
    private PlayerStatBatch() {}

    record TargetChange(UUID uuid, String name, List<AdminStats.Adjustment> changes) {
        TargetChange { changes = List.copyOf(changes); }
    }

    private record AttributeState(Holder<Attribute> attribute, AttributeInstance instance,
            double base, double effective, boolean expanded, Set<AttributeModifier> modifiers, Set<AttributeModifier> permanent) {
        AttributeState(ServerPlayer player, Holder<Attribute> attribute) {
            this(attribute, AdminStats.cachedAttribute(player, attribute), AdminStats.detachedAttribute(player, attribute));
        }

        private AttributeState(Holder<Attribute> attribute, AttributeInstance cached, AttributeInstance copy) {
            this(attribute, cached, copy.getBaseValue(), copy.getValue(), ((AdminAttribute)copy).infinity$expanded(),
                Set.copyOf(copy.getModifiers()), Set.copyOf(copy.getPermanentModifiers()));
        }

        boolean finite() { return Double.isFinite(base) && Double.isFinite(effective); }

        boolean valid(ServerPlayer player) {
            var current = AdminStats.cachedAttribute(player, attribute);
            var now = AdminStats.detachedAttribute(player, attribute);
            // A harmless later native read may materialize an equivalent default.
            // An already-captured concrete instance must retain its exact identity.
            return (instance == null || current == instance) && now != null && Double.compare(now.getBaseValue(), base) == 0
                && Double.compare(now.getValue(), effective) == 0
                && ((AdminAttribute) now).infinity$expanded() == expanded
                && modifiers.equals(Set.copyOf(now.getModifiers())) && permanent.equals(Set.copyOf(now.getPermanentModifiers()));
        }
    }

    private record TargetState(AdminStatsMenu.Session session, AdminStats.Stat stat, AdminStats.Value value,
            List<AttributeState> attributes, CompoundTag originals, List<AdminStats.Adjustment> changes) {
        TargetState { attributes = List.copyOf(attributes); changes = List.copyOf(changes); }
    }

    static final class Plan {
        private final AdminStatsMenu.Session actor;
        private final List<TargetState> states;
        private final List<ServerPlayer> targets;
        private final List<TargetChange> changes;
        private final String statId;
        private final double requested;
        private final double commonBefore;
        private final String initialError;
        private boolean consumed;

        private Plan(AdminStatsMenu.Session actor, List<TargetState> states, String statId,
                double requested, double commonBefore, String error) {
            this.actor = actor;
            this.states = List.copyOf(states);
            this.targets = states.stream().map(s -> s.session().player()).toList();
            this.changes = states.stream().map(s -> new TargetChange(s.session().player().getUUID(),
                s.session().player().getName().getString(), s.changes())).toList();
            this.statId = statId;
            this.requested = requested;
            this.commonBefore = commonBefore;
            this.initialError = error;
        }

        List<ServerPlayer> targets() { return targets; }
        List<TargetChange> changes() { return changes; }
        String statId() { return statId; }
        double requested() { return requested; }
        double commonBefore() { return commonBefore; }

        /** Empty means the exact reviewed group is still eligible, without mutation. */
        String invalidReason() {
            if (!initialError.isEmpty()) return initialError;
            if (consumed) return "This group review was already used. Prepare a new review.";
            if (!actor.world().getServer().isSameThread()) return "Apply group edits on the server thread.";
            if (!actor.valid() || !AdminStatsMenu.allowed(actor.player()))
                return "Your admin session or OP4 permission changed. Nothing changed.";
            var source = actor.player().createCommandSourceStack();
            for (var state : states) {
                var target = state.session().player();
                if (!state.session().valid()) return "A selected player session changed. Nothing changed.";
                String error = AdminStats.accessError(source, target);
                if (error != null) return error;
                var stat = AdminStats.find(target, statId);
                if (!state.stat().equals(stat) || !state.value().equals(AdminStats.value(target, stat))
                    || Double.compare(AdminStats.value(target, stat).base(), commonBefore) != 0)
                    return "A selected statistic changed. Review the group again. Nothing changed.";
                if (state.attributes().stream().anyMatch(snapshot -> !snapshot.valid(target))
                    || !state.originals().equals(originals(target)))
                    return "A selected attribute, capacity or reset history changed. Nothing changed.";
                try {
                    if (!state.changes().equals(AdminStats.planSet(target, statId, requested)))
                        return "A selected capacity or allowed range changed. Nothing changed.";
                } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
            }
            return "";
        }

        AdminStats.Result apply() {
            String error = invalidReason();
            // Consume even a stale/rejected confirmation; later changes cannot revive it.
            consumed = true;
            if (!error.isEmpty()) return new AdminStats.Result(false, error);
            var source = actor.player().createCommandSourceStack();
            int completed = 0;
            // Full preflight above completes before the first edit. This synchronous
            // loop uses the existing native setter and its original-value semantics.
            for (var target : targets) {
                var result = AdminStats.set(source, target, statId, requested);
                if (!result.success()) return new AdminStats.Result(false,
                    "Group stopped after " + completed + " player(s): " + result.message());
                completed++;
            }
            return new AdminStats.Result(true, "Updated " + completed + " selected player(s): " + statId
                + " = " + AdminStatsMenu.number(requested) + ". Normal gameplay and native player saving continue.");
        }
    }

    private static CompoundTag originals(ServerPlayer player) {
        return GameModes.state(player).getCompoundOrEmpty(AdminStats.STORAGE).copy();
    }

    private static Plan invalid(String statId, double requested, String message) {
        return new Plan(null, List.of(), statId == null ? "" : statId, requested, Double.NaN, message);
    }

    static Plan prepare(ServerPlayer actor, List<ServerPlayer> selected, String statId, double requested) {
        if (actor == null || actor.connection == null || !actor.level().getServer().isSameThread()
            || !AdminStatsMenu.allowed(actor))
            return invalid(statId, requested, "Only an online OP4 admin can prepare a group edit.");
        if (selected == null || selected.isEmpty()) return invalid(statId, requested, "Select at least one online player.");
        if (statId == null || statId.isBlank()) return invalid(statId, requested, "Select a supported player statistic.");
        if (!Double.isFinite(requested)) return invalid(statId, requested, "Use a finite number. Nothing changed.");
        if ((statId.equals("health") || statId.equals("minecraft:health")) && requested == 0)
            return invalid(statId, requested, "Group health must be greater than zero. Use individual editing for a lethal change.");
        var source = actor.createCommandSourceStack();
        var ids = new HashSet<UUID>();
        var states = new ArrayList<TargetState>();
        double common = Double.NaN, normalized = Double.NaN;
        String canonical = statId;
        for (var target : selected) {
            if (target == null || !ids.add(target.getUUID()))
                return invalid(statId, requested, "Select each actual player only once. Nothing changed.");
            String error = AdminStats.accessError(source, target);
            if (error != null) return invalid(statId, requested, error);
            var stat = AdminStats.find(target, statId);
            if (stat == null) return invalid(statId, requested, "A selected player does not support that statistic.");
            var value = AdminStats.value(target, stat);
            if (!Double.isFinite(value.base()) || !Double.isFinite(value.effective()))
                return invalid(statId, requested, "A selected statistic has an invalid current value. Nothing changed.");
            if (states.isEmpty()) common = value.base();
            else if (Double.compare(value.base(), common) != 0)
                return invalid(statId, requested, "Selected players must have the same current statistic value. Nothing changed.");
            List<AdminStats.Adjustment> changes;
            try { changes = AdminStats.planSet(target, stat.id(), requested); }
            catch (IllegalArgumentException invalid) { return invalid(statId, requested, invalid.getMessage()); }
            if (stat.id().equals("health") && changes.getLast().after() <= 0)
                return invalid(statId, requested, "Group health must remain greater than zero after numeric normalization.");
            if (states.isEmpty()) { normalized = changes.getLast().after(); canonical = stat.id(); }
            else if (Double.compare(normalized, changes.getLast().after()) != 0)
                return invalid(statId, requested, "Selected players normalize this value differently. Nothing changed.");
            var attributes = new ArrayList<AttributeState>();
            var captured = new HashSet<Holder<Attribute>>();
            // All attribute edits clamp both native vital capacities. Capturing
            // them also rejects capacity changes before direct health/absorption edits.
            for (var attribute : List.of(Attributes.MAX_HEALTH, Attributes.MAX_ABSORPTION))
                if (target.getAttributes().hasAttribute(attribute) && captured.add(attribute))
                    attributes.add(new AttributeState(target, attribute));
            if (stat.attribute()) {
                var attribute = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(stat.id())).orElse(null);
                if (attribute == null || !target.getAttributes().hasAttribute(attribute))
                    return invalid(statId, requested, "A selected player attribute is no longer available.");
                if (captured.add(attribute)) attributes.add(new AttributeState(target, attribute));
            }
            if (attributes.stream().anyMatch(snapshot -> !snapshot.finite())
                || changes.stream().anyMatch(change -> !Double.isFinite(change.before()) || !Double.isFinite(change.after())))
                return invalid(statId, requested, "A selected attribute or capacity has an invalid numeric value. Nothing changed.");
            states.add(new TargetState(new AdminStatsMenu.Session(target), stat, value, attributes, originals(target), changes));
        }
        return new Plan(new AdminStatsMenu.Session(actor), states, canonical, normalized, common, "");
    }

    /** Eligible suggestions only; callers must explicitly choose their subset. */
    static List<ServerPlayer> matching(ServerPlayer actor, ServerPlayer anchor, String statId) {
        if (actor == null || anchor == null || statId == null || actor.connection == null
            || !actor.level().getServer().isSameThread() || !AdminStatsMenu.allowed(actor)) return List.of();
        var source = actor.createCommandSourceStack();
        if (AdminStats.accessError(source, anchor) != null) return List.of();
        var stat = AdminStats.find(anchor, statId);
        if (stat == null) return List.of();
        double common = AdminStats.value(anchor, stat).base();
        if (!Double.isFinite(common)) return List.of();
        return actor.level().getServer().getPlayerList().getPlayers().stream()
            .filter(target -> AdminStats.accessError(source, target) == null)
            .filter(target -> {
                var candidate = AdminStats.find(target, stat.id());
                if (candidate == null) return false;
                var value = AdminStats.value(target, candidate);
                return Double.isFinite(value.effective()) && Double.compare(value.base(), common) == 0;
            })
            .sorted(Comparator.comparing((ServerPlayer p) -> p.getName().getString().toLowerCase(Locale.ROOT))
                .thenComparing(ServerPlayer::getUUID)).toList();
    }
}
