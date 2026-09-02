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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueCompositeTeamProfileAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void composesNeutralDimensionsForSameTeamAndSource() throws Exception {
        Database db = new Database(tempDir.resolve("composite.db"));
        db.initialize();
        League league = new League(UUID.randomUUID().toString(), "sleeper", "League");
        Team team = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "Quarterback", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr", "Receiver", "WR", "MIN");
        new LeagueRepository(db).save(league);
        new LeagueValueFormatRepository(db).save(league.getId(), LeagueValueFormat.ONE_QB);
        new TeamRepository(db).save(team);
        new PlayerRepository(db).save(qb);
        new PlayerRepository(db).save(wr);
        RosterRepository rosters = new RosterRepository(db);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), wr.getId(), "BENCH"));

        String source = DynastyProcessValueImporter.SOURCE_1QB;
        PlayerValueRepository playerValues = new PlayerValueRepository(db);
        playerValues.save(PlayerValue.create(qb.getId(), 100, source, LocalDate.of(2026, 9, 2)));
        playerValues.save(PlayerValue.create(wr.getId(), 50, source, LocalDate.of(2026, 9, 2)));
        DraftPick pick = DraftPick.create(league.getId(), 2027, 1, team.getId(), team.getId());
        new DraftPickRepository(db).save(pick);
        new DraftPickValueRepository(db).save(DraftPickValue.create(pick.getId(), 50, source, LocalDate.of(2026, 9, 2)));

        var report = new LeagueCompositeTeamProfileAnalyzer(db).analyze(league.getId(), source);
        var profile = report.teams().get(0);

        assertEquals(team.getId(), profile.teamId());
        assertEquals(150.0, profile.usablePlayerValue(), 0.001);
        assertEquals(50.0, profile.usableDraftPickValue(), 0.001);
        assertEquals(200.0, profile.usableAssetValue(), 0.001);
        assertEquals(66.666, profile.starterValueSharePercent(), 0.01);
        assertEquals(50.0, profile.topAssetSharePercent(), 0.001);
        assertEquals(1, profile.positionalDepth().positions().get("QB").totalPlayers());
        assertEquals(1, profile.draftCapital().seasons().size());
    }

    @Test
    void minimumAsOfFlowsThroughEveryComponent() throws Exception {
        Database db = new Database(tempDir.resolve("stale.db"));
        db.initialize();
        League league = new League(UUID.randomUUID().toString(), "sleeper", "League");
        Team team = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "Quarterback", "QB", "CHI");
        new LeagueRepository(db).save(league);
        new LeagueValueFormatRepository(db).save(league.getId(), LeagueValueFormat.ONE_QB);
        new TeamRepository(db).save(team);
        new PlayerRepository(db).save(qb);
        new RosterRepository(db).save(new Roster(UUID.randomUUID().toString(), null, team.getId(), qb.getId(), "STARTER"));
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        new PlayerValueRepository(db).save(PlayerValue.create(qb.getId(), 100, source, LocalDate.of(2026, 9, 1)));

        var profile = new LeagueCompositeTeamProfileAnalyzer(db)
            .analyze(league.getId(), source, LocalDate.of(2026, 9, 2)).teams().get(0);

        assertEquals(0.0, profile.usablePlayerValue(), 0.001);
        assertEquals(1, profile.concentration().staleAssets());
        assertEquals(1, profile.rosterSlots().slots().get("STARTER").stalePlayers());
        assertEquals(1, profile.positionalDepth().positions().get("QB").stalePlayers());
        assertTrue(profile.draftCapital().seasons().isEmpty());
    }
}
