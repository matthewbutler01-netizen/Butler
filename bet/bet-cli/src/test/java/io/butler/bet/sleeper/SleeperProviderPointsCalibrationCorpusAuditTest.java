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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperProviderPointsCalibrationCorpusAuditTest {
    @TempDir Path tempDir;

    @Test
    void auditsEveryPersistedLeagueSeasonWithoutOutcomeBasedDropping() throws Exception {
        Database database = new Database(tempDir.resolve("corpus.db"));
        database.initialize();

        createLeague(database, "league-a", "Alpha", "team-a");
        createLeague(database, "league-b", "Beta", "team-b");
        createLeague(database, "league-c", "Gamma", "team-c");

        var scoring = new LeagueScoringSettingsRepository(database);
        scoring.replace("league-a", Map.of("rec", 1.0));
        scoring.replace("league-b", Map.of("z_custom", 1.0, "a_custom", 2.0));
        scoring.replace("league-c", Map.of("pass_sack", -0.5));

        var players = new PlayerRepository(database);
        players.save(new Player("p-a", "1001", "Alpha Back", "RB", "CHI"));
        players.save(new Player("p-c", "3001", "Gamma QB", "QB", "DET"));

        var production = new PlayerWeekProductionRepository(database);
        production.save(new PlayerWeekProduction(
            "prod-a", "p-a", 2024, 1,
            0, 0, 0, 0, 0, 5, 0, 0, 0,
            "nflverse", LocalDate.of(2025, 1, 20)));
        production.save(PlayerWeekProduction.createExactScoringV2(
            "p-c", 2025, 1,
            250, 1, 0, 10, 0, 0, 0, 0, 0,
            0, 2, 0, 0, 0, 0,
            "nflverse", LocalDate.of(2026, 1, 20)));

        var evidence = new ProviderPlayerWeekPointsEvidenceRepository(database);
        evidence.replaceSeasonSnapshot("league-a", 2024, "sleeper", LocalDate.of(2026, 9, 5),
            List.of(provider("league-a", "team-a", "1001", 2024, "5", LocalDate.of(2026, 9, 5))));
        // A newer snapshot must not create a second corpus entry for the same league-season.
        evidence.replaceSeasonSnapshot("league-a", 2024, "sleeper", LocalDate.of(2026, 9, 6),
            List.of(provider("league-a", "team-a", "1001", 2024, "5", LocalDate.of(2026, 9, 6))));
        evidence.replaceSeasonSnapshot("league-b", 2025, "sleeper", LocalDate.of(2026, 9, 6),
            List.of(provider("league-b", "team-b", "2001", 2025, "7", LocalDate.of(2026, 9, 6))));
        evidence.replaceSeasonSnapshot("league-c", 2025, "sleeper", LocalDate.of(2026, 9, 6),
            List.of(provider("league-c", "team-c", "3001", 2025, "10", LocalDate.of(2026, 9, 6))));

        assertEquals(List.of(
                new ProviderPlayerWeekPointsEvidenceRepository.LeagueSeasonRef("league-a", 2024),
                new ProviderPlayerWeekPointsEvidenceRepository.LeagueSeasonRef("league-b", 2025),
                new ProviderPlayerWeekPointsEvidenceRepository.LeagueSeasonRef("league-c", 2025)),
            evidence.findDistinctLeagueSeasons("sleeper"));

        var report = new SleeperProviderPointsCalibrationCorpusAudit(database).audit();

        assertEquals(3, report.entries().size());
        assertEquals(List.of("league-a", "league-b", "league-c"),
            report.entries().stream().map(SleeperProviderPointsCalibrationCorpusAudit.AuditEntry::leagueId).toList());

        var alpha = report.entries().get(0);
        assertEquals(SleeperProviderPointsCalibrationCorpusAudit.EntryState.CALIBRATED, alpha.state());
        assertEquals(1, alpha.providerRows());
        assertEquals(1, alpha.calibration().orElseThrow().comparableRows());
        assertEquals(1, alpha.calibration().orElseThrow().metrics().exactMatches());

        var beta = report.entries().get(1);
        assertEquals(SleeperProviderPointsCalibrationCorpusAudit.EntryState.RULE_INELIGIBLE, beta.state());
        assertEquals(List.of("a_custom", "z_custom"), beta.unsupportedNonzeroKeys());
        assertTrue(beta.calibration().isEmpty());

        var gamma = report.entries().get(2);
        assertEquals(SleeperProviderPointsCalibrationCorpusAudit.EntryState.CALIBRATED, gamma.state());
        assertEquals(0, gamma.calibration().orElseThrow().comparableRows());
        assertEquals(1, gamma.calibration().orElseThrow().nonComparableReasons().get(
            SleeperSeasonProviderPointsCalibration.NonComparableReason.EXACT_SCORING_UNAVAILABLE));

        var summary = report.summary();
        assertEquals(3, summary.leagueSeasons());
        assertEquals(2, summary.ruleEligibleLeagueSeasons());
        assertEquals(1, summary.ruleIneligibleLeagueSeasons());
        assertEquals(2, summary.calibratedLeagueSeasons());
        assertEquals(0, summary.calibrationErrorLeagueSeasons());
        assertEquals(1, summary.leagueSeasonsWithComparableRows());
        assertEquals(3, summary.providerRows());
        assertEquals(1, summary.comparableRows());
        assertEquals(1, summary.exactMatches());
        assertEquals(1, summary.withinOneHundredth());
        assertEquals(1, summary.nonComparableReasons().get(
            SleeperSeasonProviderPointsCalibration.NonComparableReason.EXACT_SCORING_UNAVAILABLE));
    }

    private static void createLeague(Database database, String leagueId, String name, String teamId)
        throws Exception {
        new LeagueRepository(database).save(new League(leagueId, "current-" + leagueId, name, 2026));
        new TeamRepository(database).save(new Team(teamId, "1", leagueId, name + " Team"));
    }

    private static ProviderPlayerWeekPointsEvidence provider(
        String leagueId,
        String teamId,
        String providerPlayerId,
        int season,
        String points,
        LocalDate asOf) {
        return ProviderPlayerWeekPointsEvidence.create(
            leagueId, teamId, "1", "provider-" + leagueId + "-" + season,
            season, 1, providerPlayerId, new BigDecimal(points),
            "sleeper", "matchup.players_points", asOf);
    }
}
