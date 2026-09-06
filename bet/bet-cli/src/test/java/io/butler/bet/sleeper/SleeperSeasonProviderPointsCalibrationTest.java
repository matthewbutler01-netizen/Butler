package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperSeasonProviderPointsCalibrationTest {
    @TempDir Path tempDir;

    @Test
    void summarizesExactSignedAbsoluteAndNearestRankDeltasDeterministically() {
        var summary = SleeperSeasonProviderPointsCalibration.summarize(List.of(
            comparison("1", "10", "10"),
            comparison("2", "10", "10.01"),
            comparison("3", "10", "9.98"),
            comparison("4", "10", "11")));

        assertEquals(4, summary.comparableRows());
        assertEquals(1, summary.exactMatches());
        assertEquals(2, summary.withinOneHundredth());
        assertDecimal("0.2475", summary.meanSignedDelta().orElseThrow());
        assertDecimal("0.2575", summary.meanAbsoluteDelta().orElseThrow());
        assertDecimal("0.01", summary.p50AbsoluteDelta().orElseThrow());
        assertDecimal("1", summary.p95AbsoluteDelta().orElseThrow());
        assertDecimal("1", summary.maxAbsoluteDelta().orElseThrow());
    }

    @Test
    void keepsMissingIdentityMissingProductionAndUnsupportedPositionOutsideComparableUniverse() throws Exception {
        Database database = database();
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of("rec", 1.0));
        var players = new PlayerRepository(database);
        players.save(new Player("p1", "1001", "Running Back", "RB", "CHI"));
        players.save(new Player("p2", "1002", "Wide Receiver", "WR", "DET"));
        players.save(new Player("p3", "1003", "Kicker", "K", "GB"));

        new PlayerWeekProductionRepository(database).save(new PlayerWeekProduction(
            "week-p1", "p1", 2025, 1,
            0, 0, 0, 0, 0, 5, 0, 0, 0,
            "nflverse", LocalDate.of(2026, 9, 5)));

        LocalDate providerAsOf = LocalDate.of(2026, 9, 6);
        List<ProviderPlayerWeekPointsEvidence> evidence = List.of(
            evidence("1001", "5", providerAsOf),
            evidence("1002", "7.5", providerAsOf),
            evidence("1003", "9", providerAsOf),
            evidence("9999", "3", providerAsOf));
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "league-1", 2025, "sleeper", providerAsOf, evidence);

        var report = new SleeperSeasonProviderPointsCalibration(database).calibrate("league-1", 2025);

        assertEquals(4, report.providerRows());
        assertEquals(3, report.identityMappedRows());
        assertEquals(1, report.comparableRows());
        assertEquals(3, report.nonComparableRows());
        assertEquals(1, report.nonComparableReasons().get(
            SleeperSeasonProviderPointsCalibration.NonComparableReason.NO_CANONICAL_PLAYER));
        assertEquals(1, report.nonComparableReasons().get(
            SleeperSeasonProviderPointsCalibration.NonComparableReason.NO_WEEKLY_PRODUCTION));
        assertEquals(1, report.nonComparableReasons().get(
            SleeperSeasonProviderPointsCalibration.NonComparableReason.UNSUPPORTED_POSITION));
        assertEquals(1, report.metrics().exactMatches());
        assertDecimal("0", report.metrics().meanSignedDelta().orElseThrow());
        assertEquals(providerAsOf, report.providerAsOf());
        assertEquals(SleeperSeasonProviderPointsCalibration.CalibrationState.REPORTED, report.state());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("calibration.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-current", "Test League", 2026));
        new TeamRepository(database).save(new Team("team-1", "1", "league-1", "Test Team"));
        return database;
    }

    private static ProviderPlayerWeekPointsEvidence evidence(
        String sleeperPlayerId, String points, LocalDate asOfDate) {
        return ProviderPlayerWeekPointsEvidence.create(
            "league-1", "team-1", "1", "sleeper-2025", 2025, 1,
            sleeperPlayerId, new BigDecimal(points), "sleeper", "matchup.players_points", asOfDate);
    }

    private static SleeperSeasonProviderPointsCalibration.Comparison comparison(
        String id, String provider, String butler) {
        BigDecimal providerPoints = new BigDecimal(provider);
        BigDecimal butlerPoints = new BigDecimal(butler);
        return new SleeperSeasonProviderPointsCalibration.Comparison(
            id, "p-" + id, "RB", 1, providerPoints, butlerPoints,
            butlerPoints.subtract(providerPoints));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)));
    }
}
