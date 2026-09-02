package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeAssetResolverTest {
    @TempDir Path tempDir;

    @Test
    void resolvesPlayerByHumanName() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);

        var result = new TradeAssetResolver(f.database)
            .resolveExpression(f.league.getId(), "Caleb Williams");

        assertTrue(result.complete());
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, result.source());
        assertEquals(f.caleb.getId(), result.tradePackage().playerIds().getFirst());
        assertTrue(result.tradePackage().draftPickIds().isEmpty());
    }

    @Test
    void resolvesQualifiedDraftPickAcrossOwnerAndOriginalTeamMetadata() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);

        var result = new TradeAssetResolver(f.database)
            .resolveExpression(f.league.getId(), "Alpha 2027 1st");

        assertTrue(result.complete());
        assertEquals(f.alphaFirstOwnedByBeta.getId(), result.tradePackage().draftPickIds().getFirst());
        assertEquals("Beta", result.terms().getFirst().resolvedCandidate().ownerTeamName());
        assertEquals("Alpha", result.terms().getFirst().resolvedCandidate().originalTeamName());
    }

    @Test
    void genericDraftPickTermRemainsAmbiguousInsteadOfGuessing() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);

        var result = new TradeAssetResolver(f.database)
            .resolveExpression(f.league.getId(), "2027 1st");

        assertFalse(result.complete());
        assertEquals(1, result.unresolvedTerms());
        assertEquals(TradeAssetResolver.ResolutionStatus.AMBIGUOUS, result.terms().getFirst().status());
        assertEquals(2, result.terms().getFirst().candidates().size());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, result::tradePackage);
        assertTrue(error.getMessage().contains("ambiguous asset '2027 1st'"));
        assertTrue(error.getMessage().contains("Alpha"));
        assertTrue(error.getMessage().contains("Beta"));
    }

    @Test
    void mixedPlusExpressionResolvesPlayerAndPickTogether() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);

        var result = new TradeAssetResolver(f.database)
            .resolveExpression(f.league.getId(), "Caleb Williams + Alpha 2027 1st");

        assertTrue(result.complete());
        assertEquals(1, result.tradePackage().playerIds().size());
        assertEquals(1, result.tradePackage().draftPickIds().size());
        assertEquals(f.caleb.getId(), result.tradePackage().playerIds().getFirst());
        assertEquals(f.alphaFirstOwnedByBeta.getId(), result.tradePackage().draftPickIds().getFirst());
    }

    @Test
    void typedAndBareStableIdsRemainSupported() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        TradeAssetResolver resolver = new TradeAssetResolver(f.database);

        var typed = resolver.resolveExpression(f.league.getId(), "player:" + f.caleb.getId()
            + " + pick:" + f.betaFirst.getId());
        var bare = resolver.resolveExpression(f.league.getId(), f.caleb.getId());

        assertTrue(typed.complete());
        assertEquals(f.caleb.getId(), typed.tradePackage().playerIds().getFirst());
        assertEquals(f.betaFirst.getId(), typed.tradePackage().draftPickIds().getFirst());
        assertTrue(bare.complete());
        assertEquals(f.caleb.getId(), bare.tradePackage().playerIds().getFirst());
    }

    @Test
    void reportsNotFoundAndRejectsDuplicateResolvedAssets() throws Exception {
        Fixture f = fixture(LeagueValueFormat.ONE_QB);
        TradeAssetResolver resolver = new TradeAssetResolver(f.database);

        var missing = resolver.resolveExpression(f.league.getId(), "Nobody McFake");
        assertEquals(TradeAssetResolver.ResolutionStatus.NOT_FOUND, missing.terms().getFirst().status());
        assertTrue(missing.failureMessage().contains("no league asset matched"));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolveExpression(
            f.league.getId(), "Caleb Williams + player:" + f.caleb.getId()));
    }

    @Test
    void explicitSourceAllowsResolutionWhenLeagueFormatIsUnknown() throws Exception {
        Fixture f = fixture(LeagueValueFormat.UNKNOWN);

        var result = new TradeAssetResolver(f.database).resolveExpression(
            f.league.getId(), "Caleb Williams", DynastyProcessValueImporter.SOURCE_1QB);

        assertTrue(result.complete());
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, result.source());
    }

    private Fixture fixture(LeagueValueFormat format) throws Exception {
        Database database = new Database(tempDir.resolve("trade-resolver.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        DraftPickRepository picks = new DraftPickRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper-league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        Player caleb = new Player(UUID.randomUUID().toString(), "caleb", "Caleb Williams", "QB", "CHI");
        Player rome = new Player(UUID.randomUUID().toString(), "rome", "Rome Odunze", "WR", "CHI");
        leagues.save(league);
        formats.save(league.getId(), format);
        teams.save(alpha);
        teams.save(beta);
        players.save(caleb);
        players.save(rome);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), caleb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), rome.getId(), "STARTER"));

        DraftPick alphaFirstOwnedByBeta = DraftPick.create(league.getId(), 2027, 1, alpha.getId(), beta.getId());
        DraftPick betaFirst = DraftPick.create(league.getId(), 2027, 1, beta.getId(), beta.getId());
        picks.save(alphaFirstOwnedByBeta);
        picks.save(betaFirst);

        return new Fixture(database, league, caleb, rome, alphaFirstOwnedByBeta, betaFirst);
    }

    private record Fixture(Database database, League league, Player caleb, Player rome,
                           DraftPick alphaFirstOwnedByBeta, DraftPick betaFirst) {}
}
