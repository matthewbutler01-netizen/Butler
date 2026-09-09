package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** BF-659 guarded replay of an exact current audited recommendation after its add/drop transaction completed. */
final class SleeperLiveWaiverPostTransactionRecommendationReplay {
    static final String POLICY_ID =
        "sleeper-live-waiver-post-transaction-recommendation-replay-v1-bf627-bf602-exact-delta-no-bf610-relaxation";
    private static final int PRODUCTION_SEASON = 2025;
    private static final Set<String> VALID_SLOTS = Set.of("STARTER", "BENCH", "RESERVE", "TAXI");

    private final Database database;
    private final LiveRosterSource liveRosterSource;

    SleeperLiveWaiverPostTransactionRecommendationReplay(Database database) {
        this(database, sleeperLeagueId -> new SleeperApiGateway().fetchRosters(sleeperLeagueId));
    }

    SleeperLiveWaiverPostTransactionRecommendationReplay(Database database, LiveRosterSource liveRosterSource) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.liveRosterSource = Objects.requireNonNull(liveRosterSource, "liveRosterSource must not be null");
    }

    ReplayResult replay(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit)
        throws SQLException, IOException, InterruptedException {
        validateTargetAndAudit(target, audit);
        String addId = requireText(audit.addSleeperPlayerId(), "audited add Sleeper id");
        String dropId = requireText(audit.dropSleeperPlayerId(), "audited drop Sleeper id");
        if (addId.equals(dropId)) {
            throw blocked("audited add/drop identities are identical");
        }

        FrozenHeaders headers = loadFrozenHeaders(audit);
        List<LiveWaiverSnapshotRepository.Entry> snapshotEntries =
            new LiveWaiverSnapshotRepository(database).entries(audit.waiverSnapshotId());
        FrozenSnapshotFrame frozen = frozenSnapshotFrame(snapshotEntries, headers);
        ImportedFrame imported = importedFrame(target, frozen.rosteredIds());
        LiveFrame live = liveFrame(target, audit, frozen, imported);
        ReplayRoster replayRoster = replayRoster(target, audit, imported, live);

        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport readiness =
            new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(target.butlerLeagueId());
        if (!audit.marketSnapshotId().equals(readiness.marketSnapshotId())) {
            throw blocked("current persisted BF-609 candidate frame no longer matches audited BF-603 snapshot");
        }

        PlayerSeasonProductionRepository productionRepository = new PlayerSeasonProductionRepository(database);
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame candidate613 = candidate613(readiness);
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame roster613 =
            roster613(target, audit, replayRoster, productionRepository);
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuditReport bf613 =
            SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.assess(
                target.butlerLeagueId(), target.sleeperUserId(), candidate613, roster613);

        SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame methodologyFrame = methodologyFrame(bf613);
        SleeperLiveWaiverCandidateRosterComparisonMethodology methodology =
            new SleeperLiveWaiverCandidateRosterComparisonMethodology(
                (leagueId, ownerId) -> methodologyFrame,
                leagueId -> new LeagueScoringSettingsRepository(database).findByLeagueId(leagueId));
        SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport bf614 =
            methodology.audit(target.butlerLeagueId(), target.sleeperUserId());

        SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame candidate615 = candidate615(readiness);
        SleeperLiveWaiverComparisonExecutionBundle.RosterFrame roster615 =
            roster615(target, audit, replayRoster, productionRepository);
        SleeperLiveWaiverComparisonExecutionBundle bundleService =
            new SleeperLiveWaiverComparisonExecutionBundle(
                (leagueId, ownerId) -> bf614,
                leagueId -> candidate615,
                (leagueId, ownerId) -> roster615,
                productionRepository::findByPlayerId);
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle =
            bundleService.run(target.butlerLeagueId(), target.sleeperUserId());

        SleeperLiveWaiverFinalRecommendationBundle.ReplayFreshnessFrame replayFreshness =
            new SleeperLiveWaiverFinalRecommendationBundle.ReplayFreshnessFrame(
                audit.marketSnapshotId(), audit.waiverSnapshotId(), audit.sleeperLeagueId(), audit.rosterId(),
                audit.season(), audit.providerStatus(), audit.providerLeg(),
                replayRoster.players().stream()
                    .map(value -> new SleeperLiveWaiverFinalRecommendationBundle.ReplayTargetPlayer(
                        value.sleeperPlayerId(), value.rosterSlot()))
                    .toList());
        SleeperLiveWaiverFinalRecommendationBundle finalBundle =
            new SleeperLiveWaiverFinalRecommendationBundle(database);
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation =
            finalBundle.replay(
                target.butlerLeagueId(), target.sleeperUserId(), bundle, replayFreshness);

        SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport evidence =
            new SleeperLiveWaiverCrossPositionTransactionEvidence(
                (leagueId, ownerId) -> bundle,
                productionRepository::findByPlayerId)
                .explain(recommendation);

        return new ReplayResult(POLICY_ID, recommendation, bundle, evidence);
    }

    private FrozenHeaders loadFrozenHeaders(GovernedRecommendationAuditRepository.AuditRecord audit) throws SQLException {
        try (Connection connection = database.openConnection()) {
            MarketHeader market;
            try (var statement = connection.prepareStatement("""
                SELECT waiver_snapshot_id, sleeper_league_id, season, provider_status, provider_leg
                FROM live_waiver_market_attention_snapshots
                WHERE id=? AND league_id=?
                """)) {
                statement.setString(1, audit.marketSnapshotId());
                statement.setString(2, audit.leagueId());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw blocked("audited BF-603 market snapshot is missing");
                    market = new MarketHeader(
                        rs.getString("waiver_snapshot_id"), rs.getString("sleeper_league_id"),
                        rs.getInt("season"), rs.getString("provider_status"), nullableInt(rs, "provider_leg"));
                    if (rs.next()) throw blocked("audited BF-603 market snapshot is duplicated");
                }
            }
            if (!audit.waiverSnapshotId().equals(market.waiverSnapshotId())
                || !audit.sleeperLeagueId().equals(market.sleeperLeagueId())
                || audit.season() != market.season()
                || !audit.providerStatus().equals(market.providerStatus())
                || !Objects.equals(audit.providerLeg(), market.providerLeg())) {
                throw blocked("audited BF-603 header does not reconcile to BF-627 lineage");
            }

            WaiverHeader waiver;
            try (var statement = connection.prepareStatement("""
                SELECT season, provider_status, provider_leg, current_roster_identity_count,
                    active_rostered_identity_count, rostered_absent_active_count
                FROM live_waiver_snapshots
                WHERE id=? AND league_id=? AND sleeper_league_id=?
                """)) {
                statement.setString(1, audit.waiverSnapshotId());
                statement.setString(2, audit.leagueId());
                statement.setString(3, audit.sleeperLeagueId());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw blocked("audited BF-602 waiver snapshot is missing");
                    waiver = new WaiverHeader(
                        rs.getInt("season"), rs.getString("provider_status"), nullableInt(rs, "provider_leg"),
                        rs.getInt("current_roster_identity_count"),
                        rs.getInt("active_rostered_identity_count"),
                        rs.getInt("rostered_absent_active_count"));
                    if (rs.next()) throw blocked("audited BF-602 waiver snapshot is duplicated");
                }
            }
            if (audit.season() != waiver.season()
                || !audit.providerStatus().equals(waiver.providerStatus())
                || !Objects.equals(audit.providerLeg(), waiver.providerLeg())) {
                throw blocked("audited BF-602 header does not reconcile to BF-627 lineage");
            }
            if (waiver.rosteredAbsentActiveCount() != 0
                || waiver.currentRosterIdentityCount() != waiver.activeRosteredIdentityCount()) {
                throw blocked("audited BF-602 snapshot is not an exact all-rostered identity frame");
            }
            return new FrozenHeaders(market, waiver);
        }
    }

    private static FrozenSnapshotFrame frozenSnapshotFrame(
        List<LiveWaiverSnapshotRepository.Entry> entries,
        FrozenHeaders headers) {
        Map<String, LiveWaiverSnapshotRepository.Entry> byId = new LinkedHashMap<>();
        Set<String> rostered = new TreeSet<>();
        for (var entry : entries) {
            if (byId.putIfAbsent(entry.sleeperPlayerId(), entry) != null) {
                throw blocked("duplicate identity in audited BF-602 entries: " + entry.sleeperPlayerId());
            }
            if (entry.rostered()) rostered.add(entry.sleeperPlayerId());
        }
        if (rostered.size() != headers.waiver().activeRosteredIdentityCount()) {
            throw blocked("audited BF-602 rostered entry count does not reconcile");
        }
        return new FrozenSnapshotFrame(Map.copyOf(byId), Set.copyOf(rostered));
    }

    private ImportedFrame importedFrame(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        Set<String> frozenRosteredIds) throws SQLException {
        TeamRepository teams = new TeamRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        Map<String, ImportedAssignment> bySleeperId = new LinkedHashMap<>();
        ImportedTeam targetTeam = null;

        for (Team team : teams.findByLeagueId(target.butlerLeagueId())) {
            String externalRosterId = requireText(team.getExternalId(), "persisted Butler team external roster id");
            int rosterId;
            try {
                rosterId = Integer.parseInt(externalRosterId);
            } catch (NumberFormatException e) {
                throw blocked("persisted Butler team has nonnumeric Sleeper roster id " + externalRosterId);
            }
            if (rosterId == target.rosterId()) {
                if (targetTeam != null) throw blocked("duplicate persisted Butler target roster id");
                targetTeam = new ImportedTeam(team.getId(), team.getName(), rosterId);
            }
            for (Roster roster : rosters.findByTeamId(team.getId())) {
                Player player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> blocked("persisted roster row references missing Butler player " + roster.getPlayerId()));
                String sleeperId = requireText(player.getExternalId(), "persisted roster Sleeper player id");
                String slot = normalizeSlot(roster.getSlot());
                ImportedAssignment assignment = new ImportedAssignment(
                    sleeperId, rosterId, slot, player.getId(), player.getDisplayName(), player.getPosition(), player.getNflTeam());
                if (bySleeperId.putIfAbsent(sleeperId, assignment) != null) {
                    throw blocked("persisted Butler roster contains duplicate Sleeper player " + sleeperId);
                }
            }
        }
        if (targetTeam == null) throw blocked("persisted Butler target roster is missing");
        if (!bySleeperId.keySet().equals(frozenRosteredIds)) {
            throw blocked("persisted Butler roster table does not exactly match audited BF-602 rostered identities");
        }
        return new ImportedFrame(targetTeam, Map.copyOf(bySleeperId));
    }

    private LiveFrame liveFrame(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit,
        FrozenSnapshotFrame frozen,
        ImportedFrame imported) throws IOException, InterruptedException {
        List<SleeperJsonParser.SleeperRoster> rosters = liveRosterSource.fetch(target.sleeperLeagueId());
        if (rosters == null || rosters.isEmpty()) throw blocked("current Sleeper rosters are empty");
        Map<String, LiveAssignment> bySleeperId = new LinkedHashMap<>();
        SleeperJsonParser.SleeperRoster targetRoster = null;
        for (var roster : rosters) {
            if (roster.rosterId() == target.rosterId()) {
                if (targetRoster != null) throw blocked("duplicate current target roster id");
                targetRoster = roster;
            }
            for (String playerId : roster.playerIds()) {
                LiveAssignment assignment = new LiveAssignment(playerId, roster.rosterId(), liveSlot(roster, playerId));
                if (bySleeperId.putIfAbsent(playerId, assignment) != null) {
                    throw blocked("current Sleeper player appears on multiple rosters: " + playerId);
                }
            }
        }
        if (targetRoster == null) throw blocked("current BF-623 target roster is missing");
        if (target.membershipRole() == SleeperPersonalizedTargetService.MembershipRole.OWNER
            && !target.sleeperUserId().equals(targetRoster.ownerId())) {
            throw blocked("current target roster owner differs from BF-623 binding");
        }

        String addId = audit.addSleeperPlayerId();
        String dropId = audit.dropSleeperPlayerId();
        Set<String> expectedCurrent = new TreeSet<>(frozen.rosteredIds());
        if (!expectedCurrent.remove(dropId)) throw blocked("audited drop was absent from BF-602 rostered identities");
        if (!expectedCurrent.add(addId)) throw blocked("audited add was already rostered in BF-602 frame");
        if (!bySleeperId.keySet().equals(expectedCurrent)) {
            throw blocked("current Sleeper league roster membership differs from BF-602 by more than the audited add/drop");
        }

        LiveAssignment add = bySleeperId.get(addId);
        if (add == null || add.rosterId() != target.rosterId()) {
            throw blocked("audited add is not currently on the BF-623 target roster");
        }
        if (bySleeperId.containsKey(dropId)) {
            throw blocked("audited drop is still rostered after completed transaction");
        }

        for (String id : frozen.rosteredIds()) {
            if (id.equals(dropId)) continue;
            ImportedAssignment before = imported.bySleeperId().get(id);
            LiveAssignment after = bySleeperId.get(id);
            if (before == null || after == null
                || before.rosterId() != after.rosterId()
                || !before.slot().equals(after.slot())) {
                throw blocked("unaffected roster assignment/slot changed since audited frame for Sleeper player " + id);
            }
        }
        return new LiveFrame(Map.copyOf(bySleeperId));
    }

    private ReplayRoster replayRoster(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit,
        ImportedFrame imported,
        LiveFrame live) {
        String addId = audit.addSleeperPlayerId();
        String dropId = audit.dropSleeperPlayerId();
        ImportedAssignment importedDrop = imported.bySleeperId().get(dropId);
        if (importedDrop == null || importedDrop.rosterId() != target.rosterId()) {
            throw blocked("audited drop was not on persisted pre-transaction target roster");
        }
        if (!SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot(importedDrop.slot())) {
            throw blocked("audited drop was not persisted in a BENCH/RESERVE replacement slot");
        }
        if (imported.bySleeperId().containsKey(addId)) {
            throw blocked("audited add appears in persisted pre-transaction roster table");
        }
        if (live.bySleeperId().get(addId).rosterId() != target.rosterId()) {
            throw blocked("audited add did not converge to target roster");
        }

        List<ReplayPlayer> players = imported.bySleeperId().values().stream()
            .filter(value -> value.rosterId() == target.rosterId())
            .map(value -> new ReplayPlayer(
                value.sleeperPlayerId(), value.butlerPlayerId(), value.displayName(), value.position(),
                value.nflTeam(), value.slot()))
            .sorted(Comparator.comparing(ReplayPlayer::rosterSlot).thenComparing(ReplayPlayer::sleeperPlayerId))
            .toList();
        if (players.isEmpty()) throw blocked("persisted pre-transaction target roster is empty");
        return new ReplayRoster(imported.targetTeam(), players);
    }

    private SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame candidate613(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport readiness) {
        int teamUnknown = readiness.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.CURRENT_TEAM_UNKNOWN).total();
        int depthMissing = readiness.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.DEPTH_EVIDENCE_MISSING).total();
        int withPrior = readiness.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION).total();
        int withoutPrior = readiness.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION).total();
        List<SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry> entries =
            readiness.candidates().stream().map(value -> {
                boolean reviewable = value.primaryStratum()
                    == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION
                    || value.primaryStratum()
                    == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
                boolean prior = value.primaryStratum()
                    == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
                return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    value.dossier().market().sleeperPlayerId(), value.dossier().market().displayName(),
                    value.dossier().market().position(), reviewable, prior);
            }).toList();
        return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
            readiness.leagueId(), readiness.marketSnapshotId(), readiness.candidateCount(),
            teamUnknown, depthMissing, withPrior, withoutPrior, entries);
    }

    private SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame roster613(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit,
        ReplayRoster replayRoster,
        PlayerSeasonProductionRepository productionRepository) throws SQLException {
        List<SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry> entries = new ArrayList<>();
        int starters = 0;
        int bench = 0;
        int reserve = 0;
        int taxi = 0;
        int present = 0;
        for (ReplayPlayer player : replayRoster.players()) {
            boolean prior = hasPriorProduction(productionRepository, player.butlerPlayerId());
            if (prior) present++;
            switch (player.rosterSlot()) {
                case "STARTER" -> starters++;
                case "BENCH" -> bench++;
                case "RESERVE" -> reserve++;
                case "TAXI" -> taxi++;
                default -> throw blocked("unsupported replay roster slot " + player.rosterSlot());
            }
            entries.add(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                player.sleeperPlayerId(), player.displayName(), player.position(), player.rosterSlot(),
                "EXACT_CANONICAL", prior));
        }
        return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame(
            target.butlerLeagueId(), audit.marketSnapshotId(), audit.waiverSnapshotId(), audit.sleeperLeagueId(),
            audit.season(), audit.providerStatus(), audit.providerLeg(), target.sleeperUserId(), audit.rosterId(),
            entries.size(), starters, bench, reserve, taxi, present, entries.size() - present, List.copyOf(entries));
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame methodologyFrame(
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuditReport report) {
        List<SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction> missing =
            report.missingRosterProduction().stream()
                .map(value -> new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
                    value.sleeperPlayerId(), value.displayName(), value.position(), value.rosterSlot()))
                .toList();
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame(
            report.leagueId(), report.sleeperOwnerId(), report.marketSnapshotId(), report.waiverSnapshotId(),
            report.sleeperLeagueId(), report.providerSeason(), report.providerStatus(), report.providerLeg(),
            report.rosterId(), report.candidateCount(), report.reviewableCandidateCount(),
            report.reviewableWithPriorProduction(), report.reviewableWithoutPriorProduction(),
            report.targetPlayerCount(), report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(),
            report.targetPriorProductionPresent(), report.targetPriorProductionMissing(), missing, report.state());
    }

    private SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame candidate615(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport readiness) {
        List<SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry> reviewable = new ArrayList<>();
        for (var value : readiness.candidates()) {
            boolean withPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
            boolean withoutPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
            if (!withPrior && !withoutPrior) continue;
            var dossier = value.dossier();
            var market = dossier.market();
            var availability = dossier.availability();
            reviewable.add(new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
                market.sleeperPlayerId(), market.displayName(), market.position(), dossier.butlerPlayerId(), withPrior,
                market.addCount(), market.dropCount(), market.netAddAttention(), market.frameMembership(),
                availability.currentTeam(), availability.currentStatus(), availability.injuryStatus(),
                availability.depthChartPosition(), availability.depthChartOrder()));
        }
        reviewable.sort(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry::sleeperPlayerId));
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            readiness.leagueId(), readiness.marketSnapshotId(), readiness.candidateCount(), reviewable.size(), reviewable);
    }

    private SleeperLiveWaiverComparisonExecutionBundle.RosterFrame roster615(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit,
        ReplayRoster replayRoster,
        PlayerSeasonProductionRepository productionRepository) throws SQLException {
        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> entries = new ArrayList<>();
        int starters = 0;
        int bench = 0;
        int reserve = 0;
        int taxi = 0;
        for (ReplayPlayer player : replayRoster.players()) {
            switch (player.rosterSlot()) {
                case "STARTER" -> starters++;
                case "BENCH" -> bench++;
                case "RESERVE" -> reserve++;
                case "TAXI" -> taxi++;
                default -> throw blocked("unsupported replay roster slot " + player.rosterSlot());
            }
            entries.add(new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
                player.sleeperPlayerId(), player.displayName(), player.position(), player.rosterSlot(),
                player.butlerPlayerId(), hasPriorProduction(productionRepository, player.butlerPlayerId())));
        }
        entries.sort(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId));
        return new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            target.butlerLeagueId(), audit.marketSnapshotId(), audit.waiverSnapshotId(), audit.sleeperLeagueId(),
            target.sleeperUserId(), audit.rosterId(), entries.size(), starters, bench, reserve, taxi, entries);
    }

    private static boolean hasPriorProduction(
        PlayerSeasonProductionRepository repository,
        String butlerPlayerId) throws SQLException {
        for (PlayerSeasonProduction row : repository.findByPlayerId(butlerPlayerId)) {
            if (row.season() == PRODUCTION_SEASON) return true;
        }
        return false;
    }

    private static String liveSlot(SleeperJsonParser.SleeperRoster roster, String playerId) {
        if (roster.taxiIds().contains(playerId)) return "TAXI";
        if (roster.reserveIds().contains(playerId)) return "RESERVE";
        if (roster.starterIds().contains(playerId)) return "STARTER";
        return "BENCH";
    }

    private static String normalizeSlot(String value) {
        String normalized = requireText(value, "persisted roster slot").toUpperCase();
        if (!VALID_SLOTS.contains(normalized)) throw blocked("unsupported persisted roster slot " + normalized);
        return normalized;
    }

    private static void validateTargetAndAudit(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(audit, "audit must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw blocked("target is not BF-623 live verified");
        }
        SleeperLiveWaiverGovernedExplanationCapture.reconcileTarget(target, audit);
        if (!SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID.equals(audit.bf618PolicyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID.equals(audit.bf619PolicyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID.equals(audit.bf620PolicyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID.equals(audit.bf624PolicyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP.name()
                .equals(audit.recommendationState())) {
            throw blocked("audit is not an exact governed add/drop recommendation eligible for post-transaction replay");
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }

    private static IllegalStateException blocked(String message) {
        return new IllegalStateException("BF-659 BLOCKED: " + message);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw blocked(field + " is blank");
        return value.trim();
    }

    @FunctionalInterface
    interface LiveRosterSource {
        List<SleeperJsonParser.SleeperRoster> fetch(String sleeperLeagueId)
            throws IOException, InterruptedException;
    }

    record ReplayResult(
        String policyId,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport evidence) {
        ReplayResult {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-659 replay policy");
            Objects.requireNonNull(recommendation);
            Objects.requireNonNull(bundle);
            Objects.requireNonNull(evidence);
        }
    }

    private record MarketHeader(
        String waiverSnapshotId, String sleeperLeagueId, int season, String providerStatus, Integer providerLeg) {}
    private record WaiverHeader(
        int season, String providerStatus, Integer providerLeg, int currentRosterIdentityCount,
        int activeRosteredIdentityCount, int rosteredAbsentActiveCount) {}
    private record FrozenHeaders(MarketHeader market, WaiverHeader waiver) {}
    private record FrozenSnapshotFrame(
        Map<String, LiveWaiverSnapshotRepository.Entry> bySleeperId, Set<String> rosteredIds) {}
    private record ImportedTeam(String butlerTeamId, String teamName, int rosterId) {}
    private record ImportedAssignment(
        String sleeperPlayerId, int rosterId, String slot, String butlerPlayerId,
        String displayName, String position, String nflTeam) {}
    private record ImportedFrame(ImportedTeam targetTeam, Map<String, ImportedAssignment> bySleeperId) {}
    private record LiveAssignment(String sleeperPlayerId, int rosterId, String slot) {}
    private record LiveFrame(Map<String, LiveAssignment> bySleeperId) {}
    private record ReplayPlayer(
        String sleeperPlayerId, String butlerPlayerId, String displayName, String position,
        String nflTeam, String rosterSlot) {}
    private record ReplayRoster(ImportedTeam targetTeam, List<ReplayPlayer> players) {}
}
