package io.butler.bet.intelligence;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Governs exact player eligibility for persisted provider lineup slots.
 *
 * <p>The policy consumes only provider-declared fantasy positions. It never falls back to a
 * player's primary position and never guesses the meaning of an unknown lineup slot.</p>
 */
public final class LineupSlotEligibilityPolicy {
    public static final String POLICY_ID =
        "lineup-slot-eligibility-v1-provider-fantasy-positions-fail-closed";

    private static final Map<String, SlotRule> RULES = Map.ofEntries(
        Map.entry("QB", starter("QB", List.of("QB"))),
        Map.entry("RB", starter("RB", List.of("RB"))),
        Map.entry("WR", starter("WR", List.of("WR"))),
        Map.entry("TE", starter("TE", List.of("TE"))),
        Map.entry("FLEX", starter("FLEX", List.of("RB", "WR", "TE"))),
        Map.entry("SUPER_FLEX", starter("SUPER_FLEX", List.of("QB", "RB", "WR", "TE"))),
        Map.entry("BN", nonStarting("BN")),
        Map.entry("IR", nonStarting("IR")),
        Map.entry("TAXI", nonStarting("TAXI")));

    public SlotRule ruleFor(String slot) {
        String exactSlot = requireText(slot, "slot");
        SlotRule rule = RULES.get(exactSlot);
        return rule == null
            ? new SlotRule(exactSlot, SlotState.UNSUPPORTED, List.of())
            : rule;
    }

    public boolean isPlayerEligible(String slot, List<String> providerFantasyPositions) {
        SlotRule rule = ruleFor(slot);
        if (rule.state() == SlotState.UNSUPPORTED) {
            throw new IllegalStateException("Unsupported lineup slot: " + rule.slot());
        }
        if (rule.state() == SlotState.NON_STARTING) return false;

        Objects.requireNonNull(providerFantasyPositions, "providerFantasyPositions must not be null");
        for (String eligiblePosition : rule.eligibleFantasyPositions()) {
            if (providerFantasyPositions.contains(eligiblePosition)) return true;
        }
        return false;
    }

    private static SlotRule starter(String slot, List<String> positions) {
        return new SlotRule(slot, SlotState.STARTING_SUPPORTED, positions);
    }

    private static SlotRule nonStarting(String slot) {
        return new SlotRule(slot, SlotState.NON_STARTING, List.of());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum SlotState {
        STARTING_SUPPORTED,
        NON_STARTING,
        UNSUPPORTED
    }

    public record SlotRule(String slot, SlotState state, List<String> eligibleFantasyPositions) {
        public SlotRule {
            requireText(slot, "slot");
            Objects.requireNonNull(state, "state must not be null");
            eligibleFantasyPositions = List.copyOf(Objects.requireNonNull(
                eligibleFantasyPositions, "eligibleFantasyPositions must not be null"));
            if (state == SlotState.STARTING_SUPPORTED && eligibleFantasyPositions.isEmpty()) {
                throw new IllegalArgumentException("supported starting slot must declare eligible positions");
            }
            if (state != SlotState.STARTING_SUPPORTED && !eligibleFantasyPositions.isEmpty()) {
                throw new IllegalArgumentException("non-starting or unsupported slot cannot declare eligible positions");
            }
        }
    }
}
