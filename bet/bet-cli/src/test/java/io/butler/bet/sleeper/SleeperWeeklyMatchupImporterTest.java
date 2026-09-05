package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
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

class SleeperWeeklyMatchupImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsHistoricalWeeklyRosterMembershipAndOrderedStartersThenReconcilesSameDayRefresh() throws Exception {
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

        var result = importer.importWeek("L1", 5);
        assertEquals(2, result.teamsImported());
        assertEquals(2026, result.season());
        assertEquals(5, result.week());
        assertEquals("sleeper", result.source());

        var first = evidence.findLatest(team1.getId(), 2026, 5, "sleeper").orElseThrow();
        assertEquals(List.of("p3", "p1", "p2"), first.providerPlayerIds());
        assertEquals(List.of("p1", "0", "p3"), first.providerStarterIds());

        gateway.secondFixture = true;
        importer.importWeek("L1", 5);
        var refreshed = evidence.findLatest(team1.getId(), 2026, 5, "sleeper").orElseThrow();

        assertEquals(first.id(), refreshed.id());
        assertEquals(List.of("p1", "p4"), refreshed.providerPlayerIds());
        assertEquals(List.of("p4"), refreshed.providerStarterIds());
    }

    @Test
    void failsClosedWhenWeeklyRosterCannotMapToAnImportedTeam() throws Exception {
        Database database = database();
        League league = new League("league-internal", "L1", "Test League", 2026);
        new LeagueRepository(database).save(league);

        var error = assertThrows(IllegalStateException.class,
            () -> new SleeperWeeklyMatchupImporter(new FakeGateway(), database).importWeek("L1", 5));
        assertEquals("Sleeper roster 1 is not mapped to an imported team", error.getMessage());
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

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("weekly-matchups.db"));
        database.initialize();
        return database;
    }

    private static final class FakeGateway implements SleeperGateway {
        boolean secondFixture;
        boolean duplicateRoster;

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
            if (duplicateRoster) {
                return List.of(
                    new SleeperMatchupParser.SleeperMatchup(1, List.of("p1"), List.of("p1")),
                    new SleeperMatchupParser.SleeperMatchup(1, List.of("p2"), List.of("p2")));
            }
            if (secondFixture) {
                return List.of(
                    new SleeperMatchupParser.SleeperMatchup(1, List.of("p1", "p4"), List.of("p4")),
                    new SleeperMatchupParser.SleeperMatchup(2, List.of("p9"), List.of("p9")));
            }
            return List.of(
                new SleeperMatchupParser.SleeperMatchup(1, List.of("p3", "p1", "p2"), List.of("p1", "0", "p3")),
                new SleeperMatchupParser.SleeperMatchup(2, List.of("p9", "p8"), List.of("p8")));
        }

        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            return Map.of();
        }
    }
}
