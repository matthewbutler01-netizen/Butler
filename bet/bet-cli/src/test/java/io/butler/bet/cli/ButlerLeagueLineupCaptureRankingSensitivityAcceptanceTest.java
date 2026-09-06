package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("acceptance")
class ButlerLeagueLineupCaptureRankingSensitivityAcceptanceTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    @TempDir Path tempDir;

    @Test
    void persistedMultiClusterEvidenceFlowsDeterministicallyThroughSupportAuditAndCliWithoutSelection() throws Exception {
        Database database = new Database(tempDir.resolve("acceptance.db"));
        database.initialize();

        seedLeague(database, "l1", "League One", 2025, List.of("a", "b"), 9, true);
        seedLeague(database, "l2", "League Two", 2026, List.of("c", "d", "e"), 10, false);

        var analyzer = new LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer(database);
        var first = analyzer.analyze(2025, 2026);
        var second = analyzer.analyze(2025, 2026);

        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.ReportState.AVAILABLE,
            first.reportState());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState.AVAILABLE,
            first.sourceCandidateStudy().studyState());
        assertEquals(first, second, "identical persisted evidence must produce identical governed reports");

        assertEquals(
            first.sourceCandidateStudy().frequencyCandidates().stream().map(item -> item.candidate()).toList(),
            first.frequencyCandidates().stream().map(item -> item.candidate()).toList(),
            "frequency candidate identity/order must remain BF-526 order");
        assertEquals(
            first.sourceCandidateStudy().magnitudeCandidates().stream().map(item -> item.candidate()).toList(),
            first.magnitudeCandidates().stream().map(item -> item.candidate()).toList(),
            "magnitude candidate identity/order must remain BF-526 order");

        boolean hasDevelopmentFoldNonGeneration = first.sourceCandidateStudy().frequencyCandidates().stream()
            .flatMap(candidate -> candidate.folds().stream())
            .anyMatch(fold -> fold.state()
                == LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState
                    .NOT_GENERATED_IN_DEVELOPMENT_FOLD);
        assertTrue(hasDevelopmentFoldNonGeneration,
            "acceptance fixture must exercise development-only candidate vocabulary/leakage boundary");

        for (var candidate : first.frequencyCandidates()) {
            assertEquals(first.sourceCandidateStudy().folds().size(), candidate.counts().totalFolds());
            assertEquals(candidate.counts().totalFolds(),
                candidate.counts().generatedFolds() + candidate.counts().notGeneratedFolds());
            assertEquals(candidate.counts().generatedFolds(),
                candidate.counts().evaluableFolds() + candidate.counts().unevaluableNoHeldOutSplitFolds());
            assertEquals(candidate.counts().evaluableFolds(), candidate.foldDirections().size());
            assertRawDirectionRows(candidate.foldDirections());
        }
        for (var candidate : first.magnitudeCandidates()) {
            assertEquals(first.sourceCandidateStudy().folds().size(), candidate.counts().totalFolds());
            assertEquals(candidate.counts().totalFolds(),
                candidate.counts().generatedFolds() + candidate.counts().notGeneratedFolds());
            assertEquals(candidate.counts().generatedFolds(),
                candidate.counts().evaluableFolds() + candidate.counts().unevaluableNoHeldOutSplitFolds());
            assertEquals(candidate.counts().evaluableFolds(), candidate.foldDirections().size());
            assertRawDirectionRows(candidate.foldDirections());
        }

        String firstOutput = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.print(first));
        String secondOutput = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.print(second));

        assertEquals(firstOutput, secondOutput, "identical reports must render deterministic CLI evidence");
        assertTrue(firstOutput.contains("Audit state: AVAILABLE"));
        assertTrue(firstOutput.contains("Frequency candidates (BF-526 order preserved"));
        assertTrue(firstOutput.contains("Maximum-movement candidates (BF-526 order preserved"));
        assertTrue(firstOutput.contains("Fold counts are league-season cluster counts, not team-cutoff sample N."));
        assertTrue(firstOutput.contains("support state:"));
        assertTrue(firstOutput.contains("direction="));
        assertTrue(firstOutput.contains("absolute temporal rank displacement:"));
        assertTrue(firstOutput.contains("does not normalize those totals into a scalar score"));
        assertTrue(firstOutput.contains("select or break ties among candidates"));
        assertTrue(firstOutput.contains("score manager consistency/quality"));
        assertFalse(firstOutput.toLowerCase().contains("selected threshold:"));
        assertFalse(firstOutput.toLowerCase().contains("best candidate:"));
        assertFalse(firstOutput.toLowerCase().contains("winning candidate:"));

        assertEquals(
            ButlerCommandRouter.Route
                .LEAGUE_LINEUP_CAPTURE_RANKING_SENSITIVITY_CANDIDATE_CROSS_FOLD_SUPPORT_AUDIT,
            ButlerCommandRouter.route(new String[] {
                "league", "lineup-capture-ranking-sensitivity-candidate-cross-fold-support-audit", "2025", "2026"
            }));
    }

    @Test
    void persistedInsufficientEvidenceFailsClosedAtPublicCliSurface() throws Exception {
        Database database = new Database(tempDir.resolve("empty.db"));
        database.initialize();

        var report = new LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer(database)
            .analyze(2025, 2026);
        String output = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.print(report));

        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.ReportState
                .UNAVAILABLE_CANDIDATE_STUDY,
            report.reportState());
        assertTrue(report.frequencyCandidates().isEmpty());
        assertTrue(report.magnitudeCandidates().isEmpty());
        assertTrue(output.contains("Audit state: UNAVAILABLE_CANDIDATE_STUDY"));
        assertTrue(output.contains("No candidate cross-fold support evidence is published"));
        assertFalse(output.contains("Frequency candidates (BF-526 order preserved"));
        assertFalse(output.contains("Maximum-movement candidates (BF-526 order preserved"));
        assertFalse(output.toLowerCase().contains("selected threshold:"));
    }

    private static void assertRawDirectionRows(
        List<LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.FoldDirectionAudit> directions) {
        for (var direction : directions) {
            assertTrue(direction.meetsRule().rows() > 0);
            assertTrue(direction.doesNotMeetRule().rows() > 0);
            assertEquals(direction.meetsRule().rows(),
                direction.meetsRule().absoluteTemporalRankDisplacementDistribution().values().stream()
                    .mapToInt(Integer::intValue).sum());
            assertEquals(direction.doesNotMeetRule().rows(),
                direction.doesNotMeetRule().absoluteTemporalRankDisplacementDistribution().values().stream()
                    .mapToInt(Integer::intValue).sum());
        }
    }

    private static void seedLeague(
        Database database,
        String leagueId,
        String leagueName,
        int season,
        List<String> teamPrefixes,
        int weeks,
        boolean mixedPersistence) throws Exception {
        new LeagueRepository(database).save(new League(leagueId, leagueId.toUpperCase(), leagueName, season));

        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        PlayerFantasyPositionObservationRepository positions =
            new PlayerFantasyPositionObservationRepository(database);

        for (int i = 0; i < teamPrefixes.size(); i++) {
            String prefix = teamPrefixes.get(i);
            teams.save(new Team("t" + prefix, String.valueOf(i + 1), leagueId, "Team " + prefix.toUpperCase()));
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
            positions.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
            positions.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
            positions.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
        }

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            leagueId, "sleeper", AS_OF, season, List.of("QB", "WR", "BN"),
            Map.of("pass_td", 4.0, "rec_td", 6.0)));

        for (int week = 1; week <= weeks; week++) {
            saveCompleteWeek(database, leagueId, season, teamPrefixes, week);
            saveCoverage(database, season, teamPrefixes, week);
            boolean reversed = mixedPersistence && (week > 5 || week % 2 == 0);
            saveProduction(database, season, teamPrefixes, week, reversed);
        }
    }

    private static void saveCompleteWeek(
        Database database,
        String leagueId,
        int season,
        List<String> teamPrefixes,
        int week) throws Exception {
        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        for (String prefix : teamPrefixes) {
            rosters.save(TeamWeekRosterEvidence.create(
                leagueId, "t" + prefix, season, week,
                List.of(prefix + "1", prefix + "2", prefix + "3"),
                List.of(prefix + "1", prefix + "2"),
                "sleeper", AS_OF));
        }
    }

    private static void saveCoverage(
        Database database,
        int season,
        List<String> teamPrefixes,
        int week) throws Exception {
        List<String> covered = teamPrefixes.stream()
            .flatMap(prefix -> List.of("p" + prefix + "1", "p" + prefix + "2", "p" + prefix + "3").stream())
            .toList();
        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            season, week, "nflverse",
            URI.create("https://example.test/stats_player_week_" + season + ".csv"),
            AS_OF, 100, covered.size(), 0, covered));
    }

    private static void saveProduction(
        Database database,
        int season,
        List<String> teamPrefixes,
        int week,
        boolean reversed) throws Exception {
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        for (int i = 0; i < teamPrefixes.size(); i++) {
            String prefix = teamPrefixes.get(i);
            boolean weakStarter = reversed ? i != 0 : i == 0;
            saveProductionRow(production, season, week, "p" + prefix + "1", 1, 0);
            if (weakStarter) {
                saveProductionRow(production, season, week, "p" + prefix + "2", 0, 1);
                saveProductionRow(production, season, week, "p" + prefix + "3", 0, 2);
            } else {
                saveProductionRow(production, season, week, "p" + prefix + "2", 0, 2);
                saveProductionRow(production, season, week, "p" + prefix + "3", 0, 1);
            }
        }
    }

    private static void saveProductionRow(
        PlayerWeekProductionRepository repository,
        int season,
        int week,
        String playerId,
        int passingTouchdowns,
        int receivingTouchdowns) throws Exception {
        repository.save(PlayerWeekProduction.create(
            playerId, season, week,
            0, passingTouchdowns, 0,
            0, 0,
            0, 0, receivingTouchdowns,
            0, "nflverse", AS_OF));
    }

    private static String capture(Runnable runnable) {
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(previous);
        }
    }
}
