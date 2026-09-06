package io.butler.bet.data;

import io.butler.bet.domain.League;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderPlayerWeekPointsEvidenceRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void preservesDecimalDefenseIdentityDeterministicOrderAndIdempotentReplacement() throws Exception {
        Database database = initialized();
        var repository = new ProviderPlayerWeekPointsEvidenceRepository(database);
        LocalDate asOf = LocalDate.of(2026, 9, 6);

        var chi = evidence("e1", "t2", "2", 2, "CHI", "7.2500", asOf);
        var player = evidence("e2", "t1", "1", 1, "p1", "14.7300", asOf);
        repository.replaceSeasonSnapshot("l1", 2025, "sleeper", asOf, List.of(chi, player));

        var first = repository.findSnapshot("l1", 2025, "sleeper", asOf);
        assertEquals(List.of("p1", "CHI"), first.stream().map(
            ProviderPlayerWeekPointsEvidence::providerPlayerId).toList());
        assertEquals(new BigDecimal("14.7300"), first.getFirst().points());
        assertEquals(new BigDecimal("7.2500"), first.get(1).points());

        var replacement = List.of(
            evidence("e3", "t2", "2", 2, "CHI", "8.1250", asOf),
            evidence("e4", "t1", "1", 1, "p1", "15.0000", asOf));
        repository.replaceSeasonSnapshot("l1", 2025, "sleeper", asOf, replacement);

        var second = repository.findLatestByLeagueSeason("l1", 2025, "sleeper");
        assertEquals(2, second.size());
        assertEquals(new BigDecimal("15.0000"), second.getFirst().points());
        assertEquals(new BigDecimal("8.1250"), second.get(1).points());
        assertEquals("matchup.players_points", second.get(1).sourceSurface());
        assertEquals("hist", second.get(1).providerLeagueId());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("provider-points-repository.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "cur", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));
        return database;
    }

    private static ProviderPlayerWeekPointsEvidence evidence(
        String id, String teamId, String rosterId, int week, String playerId,
        String points, LocalDate asOf) {
        return new ProviderPlayerWeekPointsEvidence(
            id, "l1", teamId, rosterId, "hist", 2025, week, playerId,
            new BigDecimal(points), "sleeper", "matchup.players_points", asOf);
    }
}
