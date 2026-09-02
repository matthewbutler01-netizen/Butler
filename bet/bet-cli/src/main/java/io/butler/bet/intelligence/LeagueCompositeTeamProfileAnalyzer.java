package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes Butler's neutral team-analysis dimensions into one profile without adding strategy
 * labels, thresholds, grades, or recommendations. Component analyzers remain the source of truth.
 */
public final class LeagueCompositeTeamProfileAnalyzer {
    private final LeagueValueSourceResolver sources;
    private final LeagueAssetConcentrationAnalyzer concentration;
    private final LeagueRosterSlotValueAnalyzer rosterSlots;
    private final LeaguePositionalDepthAnalyzer positionalDepth;
    private final LeagueDraftCapitalTimelineAnalyzer draftCapital;

    public LeagueCompositeTeamProfileAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.sources = new LeagueValueSourceResolver(database);
        this.concentration = new LeagueAssetConcentrationAnalyzer(database);
        this.rosterSlots = new LeagueRosterSlotValueAnalyzer(database);
        this.positionalDepth = new LeaguePositionalDepthAnalyzer(database);
        this.draftCapital = new LeagueDraftCapitalTimelineAnalyzer(database);
    }

    public CompositeProfileReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), null);
    }

    public CompositeProfileReport analyze(String leagueId, String source) throws SQLException {
        return analyze(leagueId, source, null);
    }

    public CompositeProfileReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), minimumAsOfDate);
    }

    public CompositeProfileReport analyze(String leagueId, String source, LocalDate minimumAsOfDate)
        throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");

        var concentrationReport = concentration.analyze(normalizedLeagueId, normalizedSource, minimumAsOfDate);
        var slotReport = rosterSlots.analyze(normalizedLeagueId, normalizedSource, minimumAsOfDate);
        var depthReport = positionalDepth.analyze(normalizedLeagueId, normalizedSource, minimumAsOfDate);
        var draftReport = draftCapital.analyze(normalizedLeagueId, normalizedSource, minimumAsOfDate);

        Map<String, LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext> slotsByTeam = indexSlots(slotReport);
        Map<String, LeaguePositionalDepthAnalyzer.TeamDepth> depthByTeam = indexDepth(depthReport);
        Map<String, LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital> draftByTeam = indexDraft(draftReport);
        List<TeamProfile> profiles = new ArrayList<>();

        for (var team : concentrationReport.teams()) {
            profiles.add(new TeamProfile(team.teamId(), team.teamName(), team,
                required(slotsByTeam, team.teamId(), "roster-slot context"),
                required(depthByTeam, team.teamId(), "positional-depth context"),
                required(draftByTeam, team.teamId(), "draft-capital context")));
        }
        profiles.sort(Comparator.comparing(TeamProfile::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamProfile::teamId));

        return new CompositeProfileReport(normalizedLeagueId, normalizedSource, minimumAsOfDate,
            List.copyOf(profiles));
    }

    private static Map<String, LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext> indexSlots(
        LeagueRosterSlotValueAnalyzer.RosterSlotReport report) {
        Map<String, LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext> result = new HashMap<>();
        for (var team : report.teams()) result.put(team.teamId(), team);
        return result;
    }

    private static Map<String, LeaguePositionalDepthAnalyzer.TeamDepth> indexDepth(
        LeaguePositionalDepthAnalyzer.DepthReport report) {
        Map<String, LeaguePositionalDepthAnalyzer.TeamDepth> result = new HashMap<>();
        for (var team : report.teams()) result.put(team.teamId(), team);
        return result;
    }

    private static Map<String, LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital> indexDraft(
        LeagueDraftCapitalTimelineAnalyzer.DraftCapitalReport report) {
        Map<String, LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital> result = new HashMap<>();
        for (var team : report.teams()) result.put(team.teamId(), team);
        return result;
    }

    private static <T> T required(Map<String, T> map, String teamId, String label) {
        T value = map.get(teamId);
        if (value == null) throw new IllegalStateException(label + " missing for team: " + teamId);
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record TeamProfile(String teamId, String teamName,
                              LeagueAssetConcentrationAnalyzer.TeamConcentration concentration,
                              LeagueRosterSlotValueAnalyzer.TeamRosterSlotContext rosterSlots,
                              LeaguePositionalDepthAnalyzer.TeamDepth positionalDepth,
                              LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital draftCapital) {
        public TeamProfile {
            Objects.requireNonNull(concentration, "concentration must not be null");
            Objects.requireNonNull(rosterSlots, "rosterSlots must not be null");
            Objects.requireNonNull(positionalDepth, "positionalDepth must not be null");
            Objects.requireNonNull(draftCapital, "draftCapital must not be null");
        }

        public double usablePlayerValue() { return rosterSlots.totalUsablePlayerValue(); }
        public double usableDraftPickValue() { return draftCapital.value(); }
        public double usableAssetValue() { return concentration.usableAssetValue(); }
        public double starterValueSharePercent() { return rosterSlots.starterValueSharePercent(); }
        public double topAssetSharePercent() { return concentration.topAssetSharePercent(); }
        public double topThreeAssetSharePercent() { return concentration.topThreeSharePercent(); }
        public double concentrationIndex() { return concentration.herfindahlIndex(); }
    }

    public record CompositeProfileReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                         List<TeamProfile> teams) {
        public CompositeProfileReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
    }
}
