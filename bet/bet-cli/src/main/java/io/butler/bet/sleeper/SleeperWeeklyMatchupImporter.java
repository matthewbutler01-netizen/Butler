package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.TeamWeekRosterEvidence;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Imports raw week-specific Sleeper matchup roster membership without scoring or lineup inference. */
public final class SleeperWeeklyMatchupImporter {
    private static final String SOURCE = "sleeper";

    private final SleeperGateway gateway;
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final TeamWeekRosterEvidenceRepository evidence;

    public SleeperWeeklyMatchupImporter(Database database) {
        this(new SleeperApiGateway(), database);
    }

    SleeperWeeklyMatchupImporter(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.evidence = new TeamWeekRosterEvidenceRepository(database);
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
        LocalDate asOfDate = LocalDate.now(ZoneOffset.UTC);
        Set<Integer> seenRosterIds = new HashSet<>();
        int imported = 0;
        for (var matchup : matchups) {
            if (!seenRosterIds.add(matchup.rosterId())) {
                throw new IllegalStateException("Duplicate Sleeper matchup roster_id: " + matchup.rosterId());
            }
            String rosterExternalId = Integer.toString(matchup.rosterId());
            var team = teams.findByExternalId(league.getId(), rosterExternalId)
                .orElseThrow(() -> new IllegalStateException(
                    "Sleeper roster " + rosterExternalId + " is not mapped to an imported team"));
            evidence.save(TeamWeekRosterEvidence.create(
                league.getId(),
                team.getId(),
                sourceLeague.season(),
                week,
                matchup.playerIds(),
                matchup.starterIds(),
                SOURCE,
                asOfDate));
            imported++;
        }

        return new ImportResult(league.getId(), sourceLeague.season(), week, SOURCE, imported);
    }

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
