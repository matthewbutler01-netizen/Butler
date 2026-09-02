package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueHealthAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsCoreReadyEvenWhenMovementNeedsAnotherSnapshot() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        saveCompleteValues(f, DynastyProcessValueImporter.SOURCE_1QB,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        var report = new LeagueHealthAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueHealthAnalyzer.HealthStatus.READY, report.status());
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertTrue(report.automaticSource());
        assertEquals(LeagueValueFormat.ONE_QB, report.valueFormat());
        assertTrue(report.formatDetected());
        assertEquals(2, report.teams());
        assertEquals(2, report.rosteredPlayers());
        assertEquals(2, report.draftPicks());
        assertTrue(report.franchiseRankingsReady());
        assertFalse(report.movementReady());
        assertEquals(LeagueMovementReadinessAnalyzer.Readiness.UNAVAILABLE,
            report.movementReadiness().readiness());
        assertTrue(report.diagnostics().contains("Current franchise-value analysis is ready."));
    }

    @Test
    void reportsPartialWhenCurrentFranchiseValuesAreIncomplete() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));

        var report = new LeagueHealthAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueHealthAnalyzer.HealthStatus.PARTIAL, report.status());
        assertFalse(report.franchiseRankingsReady());
        assertEquals(1, report.franchiseReadiness().valuedAssets());
        assertEquals(3, report.franchiseReadiness().missingAssets());
    }

    @Test
    void unknownLeagueFormatRequiresExplicitSourceInsteadOfGuessing() throws Exception {
        Fixture f = fixture(LeagueValueFormat.UNKNOWN);

        var report = new LeagueHealthAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(LeagueHealthAnalyzer.HealthStatus.SOURCE_REQUIRED, report.status());
        assertFalse(report.sourceResolved());
        assertNull(report.source());
        assertFalse(report.automaticSource());
        assertFalse(report.formatDetected());
        assertNull(report.franchiseReadiness());
        assertNull(report.movementReadiness());
    }

    @Test
    void explicitSourceAllowsUnknownFormatLeagueToBeEvaluated() throws Exception {
        Fixture f = fixture(LeagueValueFormat.UNKNOWN);
        saveCompleteValues(f, DynastyProcessValueImporter.SOURCE_1QB,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        var report = new LeagueHealthAnalyzer(f.database).analyze(
            f.league.getId(), DynastyProcessValueImporter.SOURCE_1QB);

        assertEquals(LeagueHealthAnalyzer.HealthStatus.READY, report.status());
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
        assertFalse(report.automaticSource());
        assertFalse(report.formatDetected());
        assertTrue(report.franchiseRankingsReady());
    }

    @Test
    void explicitMinimumDateSurfacesStaleLeagueValues() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        saveCompleteValues(f, DynastyProcessValueImporter.SOURCE_1QB,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 28));

        LocalDate cutoff = LocalDate.of(2026, 8, 30);
        var report = new LeagueHealthAnalyzer(f.database).analyze(f.league.getId(), cutoff);

        assertEquals(LeagueHealthAnalyzer.HealthStatus.STALE, report.status());
        assertEquals(cutoff, report.minimumAsOfDate());
        assertEquals(2, report.franchiseReadiness().staleAssets());
        assertFalse(report.coreAnalysisReady());
        assertFalse(report.franchiseRankingsReady());
    }

    private void saveCompleteValues(Fixture f, String source,
                                    LocalDate playerDate, LocalDate pickDate) throws Exception {
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100, source, playerDate));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 80, source, playerDate));
        f.pickValues.save(DraftPickValue.create(f.alphaFirst.getId(), 70, source, pickDate));
        f.pickValues.save(DraftPickValue.create(f.betaSecond.getId(), 40, source, pickDate));
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("league-health.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository playerValues = new PlayerValueRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);
        DraftPickValueRepository pickValues = new DraftPickValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper-league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "Quarterback", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr", "Receiver", "WR", "MIN");
        leagues.save(league);
        formats.save(league.getId(), format);
        teams.save(alpha);
        teams.save(beta);
        players.save(qb);
        players.save(wr);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), wr.getId(), "STARTER"));

        DraftPick alphaFirst = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), alpha.getId());
        DraftPick betaSecond = DraftPick.create(league.getId(), 2027, 2, beta.getId(), beta.getId());
        picks.save(alphaFirst);
        picks.save(betaSecond);

        return new Fixture(database, league, qb, wr, alphaFirst, betaSecond, playerValues, pickValues);
    }

    private record Fixture(Database database, League league, Player qb, Player wr,
                           DraftPick alphaFirst, DraftPick betaSecond,
                           PlayerValueRepository playerValues, DraftPickValueRepository pickValues) {}
}
