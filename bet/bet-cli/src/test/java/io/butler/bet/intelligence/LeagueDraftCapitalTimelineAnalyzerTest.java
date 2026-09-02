package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeagueDraftCapitalTimelineAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void aggregatesDraftCapitalByCurrentOwnerAndSeason() throws Exception {
        Fixture f = fixture();
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        f.values.save(DraftPickValue.create(f.alpha2027First.getId(), 80, source, LocalDate.of(2026, 9, 1)));
        f.values.save(DraftPickValue.create(f.beta2027Second.getId(), 45, source, LocalDate.of(2026, 9, 1)));
        f.values.save(DraftPickValue.create(f.traded2028First.getId(), 70, source, LocalDate.of(2026, 9, 1)));

        var report = new LeagueDraftCapitalTimelineAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(3, report.totalPicks());
        assertEquals(3, report.valuedPicks());
        assertEquals(195.0, report.totalValue());

        var alpha = report.teams().stream().filter(team -> team.teamName().equals("Alpha")).findFirst().orElseThrow();
        assertEquals(2, alpha.totalPicks());
        assertEquals(150.0, alpha.value());
        assertEquals(2, alpha.seasons().size());
        assertEquals(1, alpha.seasons().stream().filter(season -> season.season() == 2028)
            .findFirst().orElseThrow().roundCounts().get(1));

        var beta = report.teams().stream().filter(team -> team.teamName().equals("Beta")).findFirst().orElseThrow();
        assertEquals(1, beta.totalPicks());
        assertEquals(45.0, beta.value());
    }

    @Test
    void minimumAsOfExcludesStalePickValueButKeepsStaleCoverageVisible() throws Exception {
        Fixture f = fixture();
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        f.values.save(DraftPickValue.create(f.alpha2027First.getId(), 80, source, LocalDate.of(2026, 8, 30)));
        f.values.save(DraftPickValue.create(f.beta2027Second.getId(), 45, source, LocalDate.of(2026, 9, 1)));

        var report = new LeagueDraftCapitalTimelineAnalyzer(f.database).analyze(
            f.league.getId(), LocalDate.of(2026, 9, 1));

        assertEquals(3, report.totalPicks());
        assertEquals(1, report.valuedPicks());
        assertEquals(1, report.stalePicks());
        assertEquals(1, report.missingPicks());
        assertEquals(45.0, report.totalValue());
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("draft-capital-" + UUID.randomUUID() + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);
        DraftPickValueRepository values = new DraftPickValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        teams.save(beta);

        DraftPick alpha2027First = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), alpha.getId());
        DraftPick beta2027Second = DraftPick.create(league.getId(), 2027, 2, beta.getId(), beta.getId());
        DraftPick traded2028First = DraftPick.create(league.getId(), 2028, 1, beta.getId(), alpha.getId());
        picks.save(alpha2027First);
        picks.save(beta2027Second);
        picks.save(traded2028First);

        return new Fixture(database, league, alpha2027First, beta2027Second, traded2028First, values);
    }

    private record Fixture(Database database, League league,
                           DraftPick alpha2027First, DraftPick beta2027Second,
                           DraftPick traded2028First, DraftPickValueRepository values) {}
}
