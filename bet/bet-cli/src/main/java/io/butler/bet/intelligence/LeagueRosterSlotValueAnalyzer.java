package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Describes where usable player value is stored across persisted roster slots. This is neutral
 * roster allocation context only; no preferred starter/bench split or roster-construction target
 * is inferred.
 */
public final class LeagueRosterSlotValueAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final RosterRepository rosters;
    private final PlayerValueRepository values;

    public LeagueRosterSlotValueAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.values = new PlayerValueRepository(database);
    }

    public RosterSlotReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), null);
    }

    public RosterSlotReport analyze(String leagueId, String source) throws SQLException {
        return analyze(leagueId, source, null);
    }

    public RosterSlotReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), minimumAsOfDate);
    }

    public RosterSlotReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var league = leagues.analyze(normalizedLeagueId);
        Map<String, MutableSlot> leagueSlots = new TreeMap<>();
        List<TeamRosterSlotContext> teams = new ArrayList<>();

        for (var team : league.teams()) {
            Map<String, MutableSlot> teamSlots = new TreeMap<>();
            for (var roster : rosters.findByTeamId(team.teamId())) {
                String slot = normalizeSlot(roster.getSlot());
                MutableSlot teamSlot = teamSlots.computeIfAbsent(slot, ignored -> new MutableSlot());
                MutableSlot leagueSlot = leagueSlots.computeIfAbsent(slot, ignored -> new MutableSlot());
                teamSlot.totalPlayers++;
                leagueSlot.totalPlayers++;

                var value = values.findLatestByPlayerIdAndSource(roster.getPlayerId(), normalizedSource).orElse(null);
                if (value == null) {
                    teamSlot.missingPlayers++;
                    leagueSlot.missingPlayers++;
                    continue;
                }
                if (minimumAsOfDate != null && value.getAsOfDate().isBefore(minimumAsOfDate)) {
                    teamSlot.stalePlayers++;
                    leagueSlot.stalePlayers++;
                    continue;
                }
                teamSlot.valuedPlayers++;
                teamSlot.value += value.getValue();
                leagueSlot.valuedPlayers++;
                leagueSlot.value += value.getValue();
            }
            teams.add(new TeamRosterSlotContext(team.teamId(), team.teamName(), freeze(teamSlots)));
        }

        teams.sort(java.util.Comparator.comparing(TeamRosterSlotContext::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamRosterSlotContext::teamId));
        return new RosterSlotReport(normalizedLeagueId, normalizedSource, minimumAsOfDate,
            freeze(leagueSlots), List.copyOf(teams));
    }

    private static Map<String, SlotValue> freeze(Map<String, MutableSlot> source) {
        Map<String, SlotValue> result = new LinkedHashMap<>();
        source.forEach((slot, value) -> result.put(slot,
            new SlotValue(slot, value.value, value.valuedPlayers, value.stalePlayers,
                value.missingPlayers, value.totalPlayers)));
        return Map.copyOf(result);
    }

    private static String normalizeSlot(String slot) {
        if (slot == null || slot.isBlank()) return "OTHER";
        String normalized = slot.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STARTER", "BENCH", "RESERVE", "TAXI" -> normalized;
            default -> "OTHER";
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final class MutableSlot {
        double value;
        int valuedPlayers;
        int stalePlayers;
        int missingPlayers;
        int totalPlayers;
    }

    public record RosterSlotReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                   Map<String, SlotValue> leagueSlots,
                                   List<TeamRosterSlotContext> teams) {
        public RosterSlotReport {
            leagueSlots = Map.copyOf(Objects.requireNonNull(leagueSlots, "leagueSlots must not be null"));
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }

        public double totalUsablePlayerValue() {
            return leagueSlots.values().stream().mapToDouble(SlotValue::value).sum();
        }
    }

    public record TeamRosterSlotContext(String teamId, String teamName, Map<String, SlotValue> slots) {
        public TeamRosterSlotContext {
            slots = Map.copyOf(Objects.requireNonNull(slots, "slots must not be null"));
        }

        public double totalUsablePlayerValue() {
            return slots.values().stream().mapToDouble(SlotValue::value).sum();
        }

        public double starterValueSharePercent() {
            if (totalUsablePlayerValue() <= 0.0) return 0.0;
            return slots.getOrDefault("STARTER", SlotValue.empty("STARTER")).value()
                * 100.0 / totalUsablePlayerValue();
        }
    }

    public record SlotValue(String slot, double value, int valuedPlayers,
                            int stalePlayers, int missingPlayers, int totalPlayers) {
        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers;
        }

        static SlotValue empty(String slot) { return new SlotValue(slot, 0.0, 0, 0, 0, 0); }
    }
}
