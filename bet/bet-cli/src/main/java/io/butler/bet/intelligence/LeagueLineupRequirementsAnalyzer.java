package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Interprets persisted league lineup slots without assigning positional pressure or recommendations. */
public final class LeagueLineupRequirementsAnalyzer {
    public static final String POLICY_ID = "lineup-requirements-v1-direct-plus-flex-exposure";
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final LeagueLineupConfigurationRepository lineupConfiguration;

    public LeagueLineupRequirementsAnalyzer(Database database) {
        this.lineupConfiguration = new LeagueLineupConfigurationRepository(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public LineupRequirementsReport analyze(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        return interpret(normalizedLeagueId, lineupConfiguration.findByLeagueId(normalizedLeagueId));
    }

    public static LineupRequirementsReport interpret(String leagueId, List<String> slots) {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Objects.requireNonNull(slots, "slots must not be null");
        Map<String, Integer> direct = new LinkedHashMap<>();
        CORE_POSITIONS.forEach(position -> direct.put(position, 0));
        int flex = 0;
        int superFlex = 0;
        int bench = 0;
        int reserve = 0;
        int taxi = 0;
        List<String> otherStarterSlots = new ArrayList<>();
        List<String> unknownSlots = new ArrayList<>();

        for (String raw : slots) {
            String slot = requireText(raw, "slot").toUpperCase(Locale.ROOT);
            if (direct.containsKey(slot)) {
                direct.put(slot, direct.get(slot) + 1);
                continue;
            }
            switch (slot) {
                case "FLEX", "WRRB_FLEX", "REC_FLEX" -> flex++;
                case "SUPER_FLEX", "SUPERFLEX" -> superFlex++;
                case "BN", "BENCH" -> bench++;
                case "IR", "RESERVE" -> reserve++;
                case "TAXI" -> taxi++;
                case "K", "DEF", "DST", "DL", "LB", "DB", "IDP_FLEX" -> otherStarterSlots.add(slot);
                default -> unknownSlots.add(slot);
            }
        }

        return new LineupRequirementsReport(normalizedLeagueId, POLICY_ID, !slots.isEmpty(),
            Map.copyOf(direct), flex, superFlex, bench, reserve, taxi,
            List.copyOf(otherStarterSlots), List.copyOf(unknownSlots), List.copyOf(slots));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record LineupRequirementsReport(
        String leagueId,
        String policyId,
        boolean available,
        Map<String, Integer> directStarterRequirements,
        int flexSlots,
        int superFlexSlots,
        int benchSlots,
        int reserveSlots,
        int taxiSlots,
        List<String> otherStarterSlots,
        List<String> unknownSlots,
        List<String> sourceSlots) {
        public LineupRequirementsReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(policyId, "policyId must not be null");
            directStarterRequirements = Map.copyOf(Objects.requireNonNull(directStarterRequirements, "directStarterRequirements must not be null"));
            otherStarterSlots = List.copyOf(Objects.requireNonNull(otherStarterSlots, "otherStarterSlots must not be null"));
            unknownSlots = List.copyOf(Objects.requireNonNull(unknownSlots, "unknownSlots must not be null"));
            sourceSlots = List.copyOf(Objects.requireNonNull(sourceSlots, "sourceSlots must not be null"));
            if (flexSlots < 0 || superFlexSlots < 0 || benchSlots < 0 || reserveSlots < 0 || taxiSlots < 0) {
                throw new IllegalArgumentException("lineup slot counts must be non-negative");
            }
        }
    }
}
