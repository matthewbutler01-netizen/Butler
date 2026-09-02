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

class LeagueAssetConcentrationAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsTopAssetSharesAcrossPlayersAndCurrentDraftPickOwnership() throws Exception {
        Fixture f = fixture();
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100, f.source, LocalDate.of(2026, 9, 2)));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 50, f.source, LocalDate.of(2026, 9, 2)));
        f.pickValues.save(DraftPickValue.create(f.pick.getId(), 50, f.source, LocalDate.of(2026, 9, 2)));

        var report = new LeagueAssetConcentrationAnalyzer(f.database).analyze(f.league.getId());
        var team = report.teams().get(0);

        assertEquals(200.0, team.usableAssetValue(), 0.001);
        assertEquals(50.0, team.topAssetSharePercent(), 0.001);
        assertEquals(100.0, team.topThreeSharePercent(), 0.001);
        assertEquals(0.375, team.herfindahlIndex(), 0.0001);
        assertEquals(3, team.valuedAssets());
        assertEquals(3, team.totalAssets());
        assertEquals(LeagueAssetConcentrationAnalyzer.AssetType.PLAYER, team.topAssets(1).get(0).type());
    }

    @Test
    void staleAndMissingAssetsStayVisibleButDoNotInflateUsableConcentration() throws Exception {
        Fixture f = fixture();
        f.playerValues.save(PlayerValue.create(f.qb.getId(), 100, f.source, LocalDate.of(2026, 9, 2)));
        f.playerValues.save(PlayerValue.create(f.wr.getId(), 80, f.source, LocalDate.of(2026, 8, 20)));

        var report = new LeagueAssetConcentrationAnalyzer(f.database)
            .analyze(f.league.getId(), LocalDate.of(2026, 9, 1));
        var team = report.teams().get(0);

        assertEquals(100.0, team.usableAssetValue(), 0.001);
        assertEquals(1, team.valuedAssets());
        assertEquals(1, team.staleAssets());
        assertEquals(1, team.missingAssets());
        assertEquals(3, team.totalAssets());
        assertEquals(100.0, team.topAssetSharePercent(), 0.001);
        assertEquals(1.0, team.herfindahlIndex(), 0.0001);
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("asset-concentration-" + UUID.randomUUID() + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);
        PlayerValueRepository playerValues = new PlayerValueRepository(database);
        DraftPickValueRepository pickValues = new DraftPickValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper-league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "Quarterback", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr", "Receiver", "WR", "MIN");
        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        players.save(qb);
        players.save(wr);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), wr.getId(), "BENCH"));
        DraftPick pick = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), alpha.getId());
        picks.save(pick);

        return new Fixture(database, league, qb, wr, pick, playerValues, pickValues,
            DynastyProcessValueImporter.SOURCE_1QB);
    }

    private record Fixture(Database database, League league, Player qb, Player wr, DraftPick pick,
                           PlayerValueRepository playerValues, DraftPickValueRepository pickValues,
                           String source) {}
}
