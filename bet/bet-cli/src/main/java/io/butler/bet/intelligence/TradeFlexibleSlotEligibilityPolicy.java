package io.butler.bet.intelligence;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit lineup-slot eligibility contract for trade recommendation evidence.
 * FLEX and SUPERFLEX remain separate exposure types; this policy does not allocate players,
 * assign pressure, weight positions, or emit a recommendation/veto.
 */
public final class TradeFlexibleSlotEligibilityPolicy {
    public static final String POLICY_ID = "trade-flexible-slot-eligibility-v1-explicit-lineup";

    private static final Set<String> FLEX_POSITIONS = Set.of("RB", "WR", "TE");
    private static final Set<String> SUPERFLEX_POSITIONS = Set.of("QB", "RB", "WR", "TE");

    private TradeFlexibleSlotEligibilityPolicy() {}

    public enum SlotType {
        FLEX,
        SUPERFLEX
    }

    public static Set<String> eligiblePositions(SlotType slotType) {
        Objects.requireNonNull(slotType, "slotType must not be null");
        return switch (slotType) {
            case FLEX -> FLEX_POSITIONS;
            case SUPERFLEX -> SUPERFLEX_POSITIONS;
        };
    }

    public static boolean isEligible(SlotType slotType, String position) {
        return eligiblePositions(slotType).contains(normalizePosition(position));
    }

    public static Exposure exposure(int flexSlots, int superFlexSlots) {
        return new Exposure(flexSlots, superFlexSlots);
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) {
            throw new IllegalArgumentException("position must not be blank");
        }
        return position.trim().toUpperCase(Locale.ROOT);
    }

    public record Exposure(int flexSlots, int superFlexSlots) {
        public Exposure {
            if (flexSlots < 0 || superFlexSlots < 0) {
                throw new IllegalArgumentException("flexible-slot exposure counts must be non-negative");
            }
        }

        public int slots(SlotType slotType) {
            Objects.requireNonNull(slotType, "slotType must not be null");
            return switch (slotType) {
                case FLEX -> flexSlots;
                case SUPERFLEX -> superFlexSlots;
            };
        }

        public boolean active(SlotType slotType) {
            return slots(slotType) > 0;
        }
    }
}
