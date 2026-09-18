package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekMatchupEvidenceRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekMatchupEvidence;
import io.butler.bet.domain.TeamWeekRosterEvidence;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Imports raw week-specific Sleeper roster evidence plus exact matchup pairing without inference. */
public final class SleeperWeeklyMatchupImporter {
    private static final String SOURCE = "sleeper";

    private final SleeperGateway gateway;
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final TeamWeekRosterEvidenceRepository evidence;
    private final TeamWeekMatchupEvidenceRepository matchupEvidence;

    public SleeperWeeklyMatchupImporter(Database database) {
        this(new SleeperApiGateway(), database);
    }

    SleeperWeeklyMatchupImporter(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.evidence = new TeamWeekRosterEvidenceRepository(database);
        this.matchupEvidence = new TeamWeekMatchupEvidenceRepository(database);
    }

    public ImportResult importWeek(String sleeperLeagueId, int week)
        throws IOException, InterruptedException, SQLException {
        if (sleeperLeagueId == null || sleeperLeagueId.isBlank()) {
            throw new IllegalArgumentException("sleeperLeagueId must not be blank");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");

        var sourceLeague = gateway.fetchLeague(sleeperLeagueId);
        if (sourceLeague.season() < 1999 || sourceLeague.season() > 2100) {
            throw new IllegalStateException("Sleeper league season is unavailable or invalid");
        }
        var league = leagues.findByExternalId(sourceLeague.id())
            .orElseThrow(() -> new IllegalStateException(
                "League must be imported before weekly matchup evidence: " + sourceLeague.id()));
        if (league.getSeason() != null && league.getSeason() != sourceLeague.season()) {
            throw new IllegalStateException(
                "Persisted league season does not match Sleeper season for " + sourceLeague.id());
        }

        var matchups = gateway.fetchMatchups(sleeperLeagueId, week);
        SleeperMatchupParser.requireExactPairing(matchups);

        List<ResolvedMatchup> resolved = new ArrayList<>();
        for (var matchup : matchups) {
            String rosterExternalId = Integer.toString(matchup.rosterId());
            Team team = teams.findByExternalId(league.getId(), rosterExternalId)
                .orElseThrow(() -> new IllegalStateException(
                    "Sleeper roster " + rosterExternalId + " is not mapped to an imported team"));
            resolved.add(new ResolvedMatchup(team, matchup));
        }

        LocalDate asOfDate = LocalDate.now(ZoneOffset.UTC);
        for (ResolvedMatchup row : resolved) {
            var matchup = row.matchup();
            evidence.save(TeamWeekRosterEvidence.create(
                league.getId(),
                row.team().getId(),
                sourceLeague.season(),
                week,
                matchup.playerIds(),
                matchup.starterIds(),
                SOURCE,
                asOfDate));
            matchupEvidence.save(TeamWeekMatchupEvidence.create(
                league.getId(),
                row.team().getId(),
                sourceLeague.season(),
                week,
                matchup.matchupId(),
                SOURCE,
                asOfDate));
        }

        return new ImportResult(
            league.getId(), sourceLeague.season(), week, SOURCE, resolved.size());
    }

    private record ResolvedMatchup(Team team, SleeperMatchupParser.SleeperMatchup matchup) {}

    public record ImportResult(String leagueId, int season, int week, String source, int teamsImported) {
        public ImportResult {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            if (!SOURCE.equals(source)) throw new IllegalArgumentException("unexpected source");
            if (teamsImported < 0) throw new IllegalArgumentException("teamsImported must not be negative");
        }
    }
}
