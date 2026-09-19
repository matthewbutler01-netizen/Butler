package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * BF-853 one-execution Dashboard summary context. Reuses the exact BF-850 live provider
 * snapshot for BF-623 target verification, BF-629 roster actionability, and BF-639
 * post-transaction convergence without persisting or carrying provider data across requests.
 */
public final class SleeperLiveWaiverSharedSummaryContext {
    private final Database database;
    private final PersonalizedSleeperTargetRepository targets;
    private final SleeperLiveWaiverSharedTargetContext.SnapshotLoader loader;
    private final SleeperJsonParser parser;

    public SleeperLiveWaiverSharedSummaryContext(Database database) {
        this(
            database,
            new PersonalizedSleeperTargetRepository(database),
            new SleeperLiveWaiverSharedTargetContext.LiveSnapshotLoader(),
            new SleeperJsonParser());
    }

    SleeperLiveWaiverSharedSummaryContext(
        Database database,
        PersonalizedSleeperTargetRepository targets,
        SleeperLiveWaiverSharedTargetContext.SnapshotLoader loader,
        SleeperJsonParser parser) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
    }

    public ResolvedSummary resolve(String butlerLeagueId)
        throws SQLException, IOException, InterruptedException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        var bound = targets.findByButlerLeagueId(leagueId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-853 BLOCKED: no personalized Sleeper target is bound for Butler league " + leagueId));

        var snapshot = Objects.requireNonNull(
            loader.load(bound.sleeperUsername(), bound.sleeperLeagueId()),
            "BF-853 shared snapshot must not be null");

        var target = new SleeperPersonalizedTargetService(database, targets, snapshot)
            .verifyBoundTarget(leagueId);

        List<SleeperJsonParser.SleeperRoster> sharedRosters = List.copyOf(
            parser.parseRosters(snapshot.rosters(target.sleeperLeagueId())));
        if (sharedRosters.isEmpty()) {
            throw new IllegalStateException("BF-853 BLOCKED: shared live roster snapshot is empty");
        }

        var summary = new SleeperLiveWaiverLatestGovernedDecisionSummary(
            database, target.sleeperLeagueId(), sharedRosters).summarize(target);

        var convergence = new SleeperLiveWaiverPostTransactionRosterConvergence(
            target.sleeperLeagueId(), sharedRosters).inspect(target, summary);

        return new ResolvedSummary(target, summary, convergence);
    }

    public record ResolvedSummary(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary,
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence) {
        public ResolvedSummary {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(summary, "summary must not be null");
            Objects.requireNonNull(convergence, "convergence must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
