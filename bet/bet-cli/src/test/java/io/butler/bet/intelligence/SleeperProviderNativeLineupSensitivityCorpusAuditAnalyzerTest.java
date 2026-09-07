package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void auditsEveryPersistedProviderLeagueSeasonBeforeDownstreamOutcome() throws Exception {
        Database database = new Database(tempDir.resolve("fixed-frame.db"));
        database.initialize();

        saveLeague(database, "ready", "Ready League", 2025, List.of("a", "b"));
        saveReadySeason(database, "ready", 2025, List.of("a", "b"), 9);

        saveLeague(database, "blocked", "Blocked League", 2026, List.of("z"));
        saveProviderOnlySnapshot(database, "blocked", 2026, "z");

        var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();

        assertEquals(SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.POLICY_ID, report.policyId());
        assertEquals(SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.FRAME_POLICY, report.framePolicy());
        assertEquals(2, report.entries().size());
        assertEquals(List.of("ready:2025", "blocked:2026"), report.entries().stream()
            .map(entry -> entry.leagueId() + ":" + entry.season()).toList());

        var ready = report.entries().get(0);
        assertEquals(
            SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.EntryState.DOWNSTREAM_AUDITED,
            ready.state());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, ready.selection().lane());
        assertEquals(HistoricalScoringLaneSelector.SelectionState.READY, ready.selection().state());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            ready.selection().scoringPolicyId());
        assertTrue(ready.downstreamAudit().isPresent());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAuditState
                .AVAILABLE_CALIBRATION_CUTOFFS,
            ready.downstreamAudit().orElseThrow().state());
        assertEquals(1, ready.downstreamAudit().orElseThrow().cutoffs().stream()
            .filter(cutoff -> cutoff.state()
                == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE)
            .count());

        var blocked = report.entries().get(1);
        assertEquals(
            SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.EntryState.PROVIDER_NATIVE_BLOCKED,
            blocked.state());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, blocked.selection().lane());
        assertEquals(HistoricalScoringLaneSelector.SelectionState.BLOCKED, blocked.selection().state());
        assertFalse(blocked.selection().blockers().isEmpty());
        assertTrue(blocked.downstreamAudit().isEmpty());

        var summary = report.summary();
        assertEquals(2, summary.fixedFrameLeagueSeasons());
        assertEquals(2, summary.distinctLeagueIds());
        assertEquals(2, summary.distinctSeasons());
        assertEquals(1, summary.providerNativeReadyLeagueSeasons());
        assertEquals(1, summary.providerNativeBlockedLeagueSeasons());
        assertEquals(1, summary.downstreamAuditedLeagueSeasons());
        assertEquals(0, summary.sourceEvidenceUnavailableLeagueSeasons());
        assertEquals(1, summary.leagueSeasonsWithAvailableCutoffs());
        assertEquals(1, summary.availableCutoffs());
        assertEquals(7, summary.excludedCutoffs());
        assertEquals(Map.of(2, 1), summary.repositoryTeamCountDistribution());
        assertEquals(Map.of(9, 1), summary.commonWeekCountDistribution());
    }

    @Test
    void keepsReadyProviderEntryWhenDownstreamSourceEvidenceIsUnavailable() throws Exception {
        Database database = new Database(tempDir.resolve("source-unavailable.db"));
        database.initialize();

        saveLeague(database, "source-missing", "Source Missing", 2025, List.of("a", "b"));
        saveRosterAndProviderEvidenceWithoutLineupPrerequisites(
            database, "source-missing", 2025, List.of("a", "b"));

        var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
        assertEquals(1, report.entries().size());
        var entry = report.entries().getFirst();
        assertEquals(HistoricalScoringLaneSelector.SelectionState.READY, entry.selection().state());
        assertEquals(
            SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.EntryState.SOURCE_EVIDENCE_UNAVAILABLE,
            entry.state());
        assertTrue(entry.downstreamAudit().isEmpty());
        assertTrue(entry.detail().isPresent());
        assertEquals(1, report.summary().fixedFrameLeagueSeasons());
        assertEquals(1, report.summary().providerNativeReadyLeagueSeasons());
        assertEquals(1, report.summary().sourceEvidenceUnavailableLeagueSeasons());
        assertEquals(0, report.summary().downstreamAuditedLeagueSeasons());
    }

    private static void saveLeague(
        Database database,
        String leagueId,
        String leagueName,
        int season,
        List<String> teamPrefixes) throws Exception {
        new LeagueRepository(database).save(new League(leagueId, leagueId.toUpperCase(), leagueName, season));
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        for (int i = 0; i < teamPrefixes.size(); i++) {
            String prefix = teamPrefixes.get(i);
            teams.save(new Team(leagueId + "-t" + prefix, String.valueOf(i + 1), leagueId,
                "Team " + prefix.toUpperCase()));
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " Quarterback", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " Receiver Two", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " Receiver Three", "WR", "MIN"));
        }
    }

    private static void saveReadySeason(
        Database database,
        String leagueId,
        int season,
        List<String> teamPrefixes,
        int weeks) throws Exception {
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            leagueId, "sleeper", AS_OF, season, List.of("QB", "WR", "BN"),
            Map.of("pass_td", 4.0, "rec_td", 6.0)));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        for (String prefix : teamPrefixes) {
            eligibility.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
            eligibility.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
            eligibility.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
        }

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        List<ProviderPlayerWeekPointsEvidence> points = new ArrayList<>();
        for (int week = 1; week <= weeks; week++) {
            for (int i = 0; i < teamPrefixes.size(); i++) {
                String prefix = teamPrefixes.get(i);
                String teamId = leagueId + "-t" + prefix;
                String providerRosterId = String.valueOf(i + 1);
                rosters.save(TeamWeekRosterEvidence.create(
                    leagueId, teamId, season, week,
                    List.of(prefix + "1", prefix + "2", prefix + "3"),
                    List.of(prefix + "1", prefix + "2"),
                    "sleeper", AS_OF));

                boolean weakStarter = i == 0;
                addProviderRow(points, leagueId, teamId, providerRosterId, season, week,
                    prefix + "1", new BigDecimal("4.0"));
                addProviderRow(points, leagueId, teamId, providerRosterId, season, week,
                    prefix + "2", weakStarter ? new BigDecimal("6.0") : new BigDecimal("12.0"));
                addProviderRow(points, leagueId, teamId, providerRosterId, season, week,
                    prefix + "3", weakStarter ? new BigDecimal("12.0") : new BigDecimal("6.0"));
            }
        }
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            leagueId, season, "sleeper", PROVIDER_AS_OF, points);
    }

    private static void saveProviderOnlySnapshot(
        Database database,
        String leagueId,
        int season,
        String prefix) throws Exception {
        List<ProviderPlayerWeekPointsEvidence> rows = new ArrayList<>();
        addProviderRow(rows, leagueId, leagueId + "-t" + prefix, "1", season, 1,
            prefix + "1", new BigDecimal("4.0"));
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            leagueId, season, "sleeper", PROVIDER_AS_OF, rows);
    }

    private static void saveRosterAndProviderEvidenceWithoutLineupPrerequisites(
        Database database,
        String leagueId,
        int season,
        List<String> teamPrefixes) throws Exception {
        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        List<ProviderPlayerWeekPointsEvidence> rows = new ArrayList<>();
        for (int i = 0; i < teamPrefixes.size(); i++) {
            String prefix = teamPrefixes.get(i);
            String teamId = leagueId + "-t" + prefix;
            String rosterId = String.valueOf(i + 1);
            rosters.save(TeamWeekRosterEvidence.create(
                leagueId, teamId, season, 1,
                List.of(prefix + "1", prefix + "2", prefix + "3"),
                List.of(prefix + "1", prefix + "2"),
                "sleeper", AS_OF));
            addProviderRow(rows, leagueId, teamId, rosterId, season, 1,
                prefix + "1", new BigDecimal("4.0"));
            addProviderRow(rows, leagueId, teamId, rosterId, season, 1,
                prefix + "2", new BigDecimal("6.0"));
            addProviderRow(rows, leagueId, teamId, rosterId, season, 1,
                prefix + "3", new BigDecimal("12.0"));
        }
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            leagueId, season, "sleeper", PROVIDER_AS_OF, rows);
    }

    private static void addProviderRow(
        List<ProviderPlayerWeekPointsEvidence> rows,
        String leagueId,
        String teamId,
        String providerRosterId,
        int season,
        int week,
        String providerPlayerId,
        BigDecimal points) {
        rows.add(ProviderPlayerWeekPointsEvidence.create(
            leagueId, teamId, providerRosterId, "provider-" + leagueId,
            season, week, providerPlayerId, points, "sleeper",
            SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF));
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);
}
