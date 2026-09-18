package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekMatchupEvidenceRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperWeeklyMatchupImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsRosterAndExactPairingThenReconcilesSameDayRefresh() throws Exception {
        Database database = database();
        League league = new League("league-internal", "L1", "Test League", 2026);
        Team team1 = new Team("team-1", "1", league.getId(), "Alpha");
        Team team2 = new Team("team-2", "2", league.getId(), "Beta");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team1);
        new TeamRepository(database).save(team2);

        FakeGateway gateway = new FakeGateway();
        SleeperWeeklyMatchupImporter importer = new SleeperWeeklyMatchupImporter(gateway, database);
        TeamWeekRosterEvidenceRepository evidence = new TeamWeekRosterEvidenceRepository(database);
        TeamWeekMatchupEvidenceRepository pairing = new TeamWeekMatchupEvidenceRepository(database);

        var result = importer.importWeek("L1", 5);
        assertEquals(2, result.teamsImported());
        assertEquals(2026, result.season());
        assertEquals(5, result.week());
        assertEquals("sleeper", result.source());

        var first = evidence.findLatest(team1.getId(), 2026, 5, "sleeper").orElseThrow();
        assertEquals(List.of("p3", "p1", "p2"), first.providerPlayerIds());
        assertEquals(List.of("p1", "0", "p3"), first.providerStarterIds());
        var firstPairing = pairing.findLatest(team1.getId(), 2026, 5, "sleeper").orElseThrow();
        assertEquals(7, firstPairing.providerMatchupId());
        assertEquals(7, pairing.findLatest(team2.getId(), 2026, 5, "sleeper")
            .orElseThrow().providerMatchupId());

        gateway.secondFixture = true;
        importer.importWeek("L1", 5);
        var refreshed = evidence.findLatest(team1.getId(), 2026, 5, "sleeper").orElseThrow();
        var refreshedPairing = pairing.findLatest(team1.getId(), 2026, 5, "sleeper").orElseThrow();

        assertEquals(first.id(), refreshed.id());
        assertEquals(List.of("p1", "p4"), refreshed.providerPlayerIds());
        assertEquals(List.of("p4"), refreshed.providerStarterIds());
        assertEquals(firstPairing.id(), refreshedPairing.id());
        assertEquals(9, refreshedPairing.providerMatchupId());
    }

    @Test
    void failsClosedBeforePersistenceWhenWeeklyRosterCannotMapToImportedTeam() throws Exception {
        Database database = database();
        League league = new League("league-internal", "L1", "Test League", 2026);
        new LeagueRepository(database).save(league);

        var error = assertThrows(IllegalStateException.class,
            () -> new SleeperWeeklyMatchupImporter(new FakeGateway(), database).importWeek("L1", 5));
        assertEquals("Sleeper roster 1 is not mapped to an imported team", error.getMessage());
        assertTrue(new TeamWeekMatchupEvidenceRepository(database)
            .findLatest("team-1", 2026, 5, "sleeper").isEmpty());
    }

    @Test
    void failsClosedOnDuplicateRosterEvidenceInOneProviderResponse() throws Exception {
        Database database = database();
        League league = new League("league-internal", "L1", "Test League", 2026);
        Team team1 = new Team("team-1", "1", league.getId(), "Alpha");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team1);

        FakeGateway gateway = new FakeGateway();
        gateway.duplicateRoster = true;
        assertThrows(IllegalStateException.class,
            () -> new SleeperWeeklyMatchupImporter(gateway, database).importWeek("L1", 5));
    }

    @Test
    void failsClosedWhenProviderWeekHasNoMatchupEvidence() throws Exception {
        Database database = database();
        League league = new League("league-internal", "L1", "Test League", 2026);
        Team team1 = new Team("team-1", "1", league.getId(), "Alpha");
        Team team2 = new Team("team-2", "2", league.getId(), "Beta");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team1);
        new TeamRepository(database).save(team2);

        FakeGateway gateway = new FakeGateway();
        gateway.emptyWeek = true;
        var error = assertThrows(IllegalStateException.class,
            () -> new SleeperWeeklyMatchupImporter(gateway, database).importWeek("L1", 5));
        assertTrue(error.getMessage().contains("has no matchup evidence"));
    }

    @Test
    void failsClosedOnUnpairedProviderMatchupEvidence() throws Exception {
        Database database = database();
        League league = new League("league-internal", "L1", "Test League", 2026);
        Team team1 = new Team("team-1", "1", league.getId(), "Alpha");
        Team team2 = new Team("team-2", "2", league.getId(), "Beta");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team1);
        new TeamRepository(database).save(team2);

        FakeGateway gateway = new FakeGateway();
        gateway.unpaired = true;
        var error = assertThrows(IllegalStateException.class,
            () -> new SleeperWeeklyMatchupImporter(gateway, database).importWeek("L1", 5));
        assertTrue(error.getMessage().contains("must pair exactly two rosters"));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("weekly-matchups.db"));
        database.initialize();
        return database;
    }

    private static final class FakeGateway implements SleeperGateway {
        boolean secondFixture;
        boolean duplicateRoster;
        boolean unpaired;
        boolean emptyWeek;

        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            return new SleeperJsonParser.SleeperLeague("L1", "Test League", List.of("QB", "FLEX"), 2026, 2, 4);
        }

        @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            return List.of();
        }

        @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return List.of();
        }

        @Override public List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String leagueId, int week) {
            if (emptyWeek) return List.of();
            if (duplicateRoster) {
                return List.of(
                    new SleeperMatchupParser.SleeperMatchup(1, 7, List.of("p1"), List.of("p1")),
                    new SleeperMatchupParser.SleeperMatchup(1, 7, List.of("p2"), List.of("p2")));
            }
            if (unpaired) {
                return List.of(
                    new SleeperMatchupParser.SleeperMatchup(1, 7, List.of("p1"), List.of("p1")),
                    new SleeperMatchupParser.SleeperMatchup(2, 8, List.of("p2"), List.of("p2")));
            }
            if (secondFixture) {
                return List.of(
                    new SleeperMatchupParser.SleeperMatchup(1, 9, List.of("p1", "p4"), List.of("p4")),
                    new SleeperMatchupParser.SleeperMatchup(2, 9, List.of("p9"), List.of("p9")));
            }
            return List.of(
                new SleeperMatchupParser.SleeperMatchup(1, 7, List.of("p3", "p1", "p2"), List.of("p1", "0", "p3")),
                new SleeperMatchupParser.SleeperMatchup(2, 7, List.of("p9", "p8"), List.of("p8")));
        }

        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            return Map.of();
        }
    }
}
