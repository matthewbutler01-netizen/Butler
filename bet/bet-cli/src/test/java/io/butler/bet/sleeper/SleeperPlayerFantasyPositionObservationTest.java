package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPlayerFantasyPositionObservationTest {
    @TempDir Path tempDir;

    @Test
    void snapshotsObservedEligibilityButDoesNotFabricateObservationForMissingPlayerMetadata() throws Exception {
        Database database = new Database(tempDir.resolve("sleeper-observation.db"));
        database.initialize();
        new SleeperLeagueImporter(new FakeGateway(), database).importLeague("L1");

        PlayerRepository players = new PlayerRepository(database);
        PlayerFantasyPositionObservationRepository observations =
            new PlayerFantasyPositionObservationRepository(database);
        var hybrid = players.findByExternalId("p1").orElseThrow();
        var explicitEmpty = players.findByExternalId("p2").orElseThrow();
        var missingMetadata = players.findByExternalId("missing").orElseThrow();

        var hybridObservation = observations.findLatest(hybrid.getId(), "sleeper").orElseThrow();
        assertEquals(List.of("WR", "RB"), hybridObservation.providerFantasyPositions());

        var emptyObservation = observations.findLatest(explicitEmpty.getId(), "sleeper").orElseThrow();
        assertTrue(emptyObservation.providerFantasyPositions().isEmpty());

        assertTrue(observations.findLatest(missingMetadata.getId(), "sleeper").isEmpty());
    }

    private static final class FakeGateway implements SleeperGateway {
        @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            return new SleeperJsonParser.SleeperLeague(
                "L1", "League", List.of("QB", "RB", "WR", "FLEX"), 2026, 2, 4);
        }

        @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            return List.of();
        }

        @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return List.of(new SleeperJsonParser.SleeperRoster(
                1, null, List.of("p1", "p2", "missing"), List.of("p1"), List.of(), List.of()));
        }

        @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            return Map.of(
                "p1", new SleeperJsonParser.SleeperPlayer(
                    "p1", "Hybrid", "WR", "CHI", null, null, List.of("WR", "RB")),
                "p2", new SleeperJsonParser.SleeperPlayer(
                    "p2", "Empty", "RB", "DET", null, null, List.of()));
        }
    }
}
