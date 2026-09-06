package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueLineupCaptureRankingSensitivityHistoricalSeasonDiscoveryTest {
    @TempDir Path tempDir;

    @Test
    void auditsPersistedHistoricalSeasonWithoutMutatingCurrentLeagueSeasonOrDuplicatingIt() throws Exception {
        Database database = new Database(tempDir.resolve("historical-season-discovery.db"));
        database.initialize();
        var leagues = new LeagueRepository(database);
        leagues.save(new League("l1", "current-external-id", "Continuing Dynasty", 2026));

        var configurations = new LeagueConfigurationObservationRepository(database);
        configurations.replace(observation(LocalDate.of(2026, 9, 5), "sleeper", 2025));
        configurations.replace(observation(LocalDate.of(2026, 9, 6), "sleeper", 2025));
        configurations.replace(observation(LocalDate.of(2026, 9, 6), "sleeper", 2026));

        var report = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(database)
            .analyze(2025, 2025);

        assertEquals(1, report.summary().requestedLeagueSeasons());
        assertEquals(1, report.summary().auditedLeagueSeasons());
        assertEquals(0, report.summary().sourceFailureLeagueSeasons());
        assertEquals(1, report.leagueSeasons().size());
        assertEquals(2025, report.leagueSeasons().get(0).season());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAuditState
                .EXCLUDED_COMMON_UNIVERSE_UNAVAILABLE,
            report.leagueSeasons().get(0).state());
        assertTrue(report.sourceFailures().isEmpty());
        assertEquals(2026, leagues.findById("l1").orElseThrow().getSeason());
    }

    private static LeagueConfigurationObservation observation(
        LocalDate asOfDate, String source, int season) {
        return new LeagueConfigurationObservation(
            "l1", source, asOfDate, season, List.of("QB", "BN"), Map.of("pass_td", 4.0));
    }
}
