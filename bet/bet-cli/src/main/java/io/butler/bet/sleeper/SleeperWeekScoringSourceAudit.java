package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only BF-558 proof audit for Sleeper's historical weekly raw-stat source.
 *
 * <p>This audit deliberately does not persist the payload, expand Butler's scoring registry,
 * infer absent stat keys as zero, or change lineup eligibility. A starter is exact-proof eligible
 * only when every nonzero league scoring key is explicitly present as a numeric value in that
 * starter's raw stat row and the resulting dot product exactly matches Sleeper matchup
 * {@code players_points}.</p>
 */
public final class SleeperWeekScoringSourceAudit {
    public static final String POLICY_ID =
        "sleeper-week-scoring-source-audit-v1-explicit-key-presence-no-missing-as-zero-read-only";
    public static final String SLEEPER_SOURCE = "sleeper";

    private final Database database;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperWeekScoringSourceAudit(Database database) {
        this(database, new LiveSource());
    }

    SleeperWeekScoringSourceAudit(Database database, Source source) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public AuditReport audit(String leagueId, int season, int week)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0 || week > 25) throw new IllegalArgumentException("week must be between 1 and 25");

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String currentSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");
        var configuration = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason(normalizedLeagueId, season, SLEEPER_SOURCE)
            .orElseThrow(() -> new IllegalStateException(
                "No Sleeper league configuration observation for requested season " + season));

        var lineage = source.resolveLineage(currentSleeperLeagueId);
        String historicalSleeperLeagueId = lineage.linksNewestToOldest().stream()
            .filter(link -> link.season() == season)
            .map(SleeperLeagueLineageResolver.LeagueLink::leagueId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Sleeper lineage from " + currentSleeperLeagueId + " does not contain season " + season));

        Map<String, Map<String, BigDecimal>> statRows = parseStatRows(source.weeklyStats(season, week));
        List<StarterObservation> starters = parseStarterObservations(
            source.matchups(historicalSleeperLeagueId, week));

        List<String> nonzeroKeys = configuration.scoringSettings().entrySet().stream()
            .filter(entry -> Double.compare(entry.getValue(), 0.0d) != 0)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

        Set<String> payloadKeys = new LinkedHashSet<>();
        statRows.values().forEach(row -> payloadKeys.addAll(row.keySet()));
        List<String> globallyAbsentKeys = nonzeroKeys.stream()
            .filter(key -> !payloadKeys.contains(key))
            .toList();

        List<StarterAudit> starterAudits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StarterObservation starter : starters) {
            if (!seen.add(starter.playerId())) continue;
            Map<String, BigDecimal> stats = statRows.get(starter.playerId());
            if (stats == null) {
                starterAudits.add(new StarterAudit(
                    starter.playerId(), isDefenseIdentity(starter.playerId()),
                    StarterState.MISSING_STAT_IDENTITY, List.copyOf(nonzeroKeys),
                    null, starter.providerPoints(), false));
                continue;
            }

            List<String> missingKeys = nonzeroKeys.stream()
                .filter(key -> !stats.containsKey(key))
                .toList();
            BigDecimal dotProduct = scoreKnownKeys(configuration.scoringSettings(), stats);
            boolean knownDotProductMatches = starter.providerPoints() != null
                && dotProduct.compareTo(starter.providerPoints()) == 0;

            StarterState state;
            if (!missingKeys.isEmpty()) {
                state = StarterState.MISSING_SCORING_KEYS;
            } else if (starter.providerPoints() == null) {
                state = StarterState.PROVIDER_POINTS_UNAVAILABLE;
            } else if (knownDotProductMatches) {
                state = StarterState.EXACT_MATCH;
            } else {
                state = StarterState.SCORE_MISMATCH;
            }
            starterAudits.add(new StarterAudit(
                starter.playerId(), isDefenseIdentity(starter.playerId()), state,
                missingKeys, dotProduct, starter.providerPoints(), knownDotProductMatches));
        }

        List<String> blockers = buildBlockers(globallyAbsentKeys, starterAudits);
        AuditState state = blockers.isEmpty()
            ? AuditState.PROOF_READY_EXACT_FOR_OBSERVED_STARTERS
            : AuditState.PROOF_BLOCKED;

        return new AuditReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            week,
            historicalSleeperLeagueId,
            configuration.asOfDate(),
            statsUri(season, week),
            configuration.lineupSlots(),
            nonzeroKeys,
            statRows.size(),
            payloadKeys.stream().sorted().toList(),
            globallyAbsentKeys,
            List.copyOf(starterAudits),
            state,
            blockers);
    }

    private Map<String, Map<String, BigDecimal>> parseStatRows(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "weekly stats payload"));
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Sleeper weekly stats payload must be a JSON object keyed by identity");
        }
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String playerId = requireText(entry.getKey(), "weekly stats identity");
            JsonNode row = entry.getValue();
            if (row == null || !row.isObject()) {
                throw new IllegalStateException("Sleeper weekly stats row must be an object for identity " + playerId);
            }
            Map<String, BigDecimal> numeric = new LinkedHashMap<>();
            row.fields().forEachRemaining(stat -> {
                if (stat.getValue() != null && stat.getValue().isNumber()) {
                    numeric.put(stat.getKey(), stat.getValue().decimalValue());
                }
            });
            result.put(playerId, Collections.unmodifiableMap(numeric));
        });
        if (result.isEmpty()) throw new IllegalStateException("Sleeper weekly stats payload contains no identities");
        return Collections.unmodifiableMap(result);
    }

    private List<StarterObservation> parseStarterObservations(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "matchup payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper matchup payload must be a JSON array");
        }
        List<StarterObservation> result = new ArrayList<>();
        for (JsonNode matchup : root) {
            JsonNode starters = matchup.get("starters");
            if (starters == null || !starters.isArray()) {
                throw new IllegalStateException("Sleeper matchup row is missing starters array");
            }
            JsonNode playerPoints = matchup.get("players_points");
            for (JsonNode starter : starters) {
                String playerId = starter == null || starter.isNull() ? null : starter.asText(null);
                if (playerId == null || playerId.isBlank() || "0".equals(playerId.trim())) continue;
                BigDecimal points = null;
                if (playerPoints != null && playerPoints.isObject()) {
                    JsonNode value = playerPoints.get(playerId);
                    if (value != null && value.isNumber()) points = value.decimalValue();
                }
                result.add(new StarterObservation(playerId.trim(), points));
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("Sleeper matchup payload contains no observed starters");
        return List.copyOf(result);
    }

    private static BigDecimal scoreKnownKeys(
        Map<String, Double> scoringSettings, Map<String, BigDecimal> stats) {
        BigDecimal total = BigDecimal.ZERO;
        for (var scoring : scoringSettings.entrySet()) {
            if (Double.compare(scoring.getValue(), 0.0d) == 0) continue;
            BigDecimal stat = stats.get(scoring.getKey());
            if (stat == null) continue;
            total = total.add(stat.multiply(BigDecimal.valueOf(scoring.getValue())));
        }
        return total.stripTrailingZeros();
    }

    private static List<String> buildBlockers(
        List<String> globallyAbsentKeys, List<StarterAudit> starterAudits) {
        List<String> blockers = new ArrayList<>();
        if (!globallyAbsentKeys.isEmpty()) {
            blockers.add("League nonzero scoring keys absent from every returned stat row: " + globallyAbsentKeys);
        }
        long missingIdentities = starterAudits.stream()
            .filter(audit -> audit.state() == StarterState.MISSING_STAT_IDENTITY).count();
        long missingKeys = starterAudits.stream()
            .filter(audit -> audit.state() == StarterState.MISSING_SCORING_KEYS).count();
        long missingProviderPoints = starterAudits.stream()
            .filter(audit -> audit.state() == StarterState.PROVIDER_POINTS_UNAVAILABLE).count();
        long mismatches = starterAudits.stream()
            .filter(audit -> audit.state() == StarterState.SCORE_MISMATCH).count();
        if (missingIdentities > 0) blockers.add(missingIdentities + " observed starter identity/identities absent from stats payload");
        if (missingKeys > 0) blockers.add(missingKeys + " observed starter row(s) omit at least one nonzero league scoring key; absence is not treated as zero");
        if (missingProviderPoints > 0) blockers.add(missingProviderPoints + " observed starter(s) have no matchup players_points value for exact comparison");
        if (mismatches > 0) blockers.add(mismatches + " observed starter(s) fail exact raw-stat dot-product comparison");
        return List.copyOf(blockers);
    }

    private static boolean isDefenseIdentity(String playerId) {
        return playerId.matches("[A-Z]{2,3}");
    }

    public static URI statsUri(int season, int week) {
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");
        if (week <= 0 || week > 25) throw new IllegalArgumentException("week must be between 1 and 25");
        return URI.create("https://api.sleeper.app/v1/stats/nfl/regular/" + season + "/" + week);
    }

    interface Source {
        SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId)
            throws IOException, InterruptedException;
        String weeklyStats(int season, int week) throws IOException, InterruptedException;
        String matchups(String sleeperLeagueId, int week) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperLeagueLineageResolver resolver = new SleeperLeagueLineageResolver();
        private final SleeperClient client = new SleeperClient();

        @Override
        public SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId)
            throws IOException, InterruptedException {
            return resolver.resolve(currentSleeperLeagueId);
        }

        @Override
        public String weeklyStats(int season, int week) throws IOException, InterruptedException {
            return client.getNflWeeklyStats(season, week);
        }

        @Override
        public String matchups(String sleeperLeagueId, int week) throws IOException, InterruptedException {
            return client.getLeagueMatchups(sleeperLeagueId, week);
        }
    }

    public enum AuditState {
        PROOF_READY_EXACT_FOR_OBSERVED_STARTERS,
        PROOF_BLOCKED
    }

    public enum StarterState {
        EXACT_MATCH,
        MISSING_STAT_IDENTITY,
        MISSING_SCORING_KEYS,
        PROVIDER_POINTS_UNAVAILABLE,
        SCORE_MISMATCH
    }

    public record StarterAudit(
        String playerId,
        boolean defenseIdentity,
        StarterState state,
        List<String> missingNonzeroScoringKeys,
        BigDecimal knownKeyDotProduct,
        BigDecimal providerPoints,
        boolean knownKeyDotProductMatchesProvider) {
        public StarterAudit {
            playerId = requireText(playerId, "playerId");
            Objects.requireNonNull(state, "state must not be null");
            missingNonzeroScoringKeys = List.copyOf(Objects.requireNonNull(
                missingNonzeroScoringKeys, "missingNonzeroScoringKeys must not be null"));
        }
    }

    public record AuditReport(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        int week,
        String sleeperLeagueId,
        LocalDate configurationAsOf,
        URI statsSourceUri,
        List<String> lineupSlots,
        List<String> nonzeroScoringKeys,
        int payloadIdentityCount,
        List<String> payloadNumericKeys,
        List<String> globallyAbsentNonzeroScoringKeys,
        List<StarterAudit> starters,
        AuditState state,
        List<String> blockers) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            Objects.requireNonNull(configurationAsOf, "configurationAsOf must not be null");
            Objects.requireNonNull(statsSourceUri, "statsSourceUri must not be null");
            lineupSlots = List.copyOf(Objects.requireNonNull(lineupSlots, "lineupSlots must not be null"));
            nonzeroScoringKeys = List.copyOf(Objects.requireNonNull(nonzeroScoringKeys, "nonzeroScoringKeys must not be null"));
            payloadNumericKeys = List.copyOf(Objects.requireNonNull(payloadNumericKeys, "payloadNumericKeys must not be null"));
            globallyAbsentNonzeroScoringKeys = List.copyOf(Objects.requireNonNull(
                globallyAbsentNonzeroScoringKeys, "globallyAbsentNonzeroScoringKeys must not be null"));
            starters = List.copyOf(Objects.requireNonNull(starters, "starters must not be null"));
            Objects.requireNonNull(state, "state must not be null");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if ((state == AuditState.PROOF_READY_EXACT_FOR_OBSERVED_STARTERS) != blockers.isEmpty()) {
                throw new IllegalArgumentException("proof-ready state must have no blockers and blocked state must have blockers");
            }
        }
    }

    private record StarterObservation(String playerId, BigDecimal providerPoints) {}

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
