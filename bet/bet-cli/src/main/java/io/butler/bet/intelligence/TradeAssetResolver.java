package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves human-readable league asset expressions into stable player/draft-pick IDs.
 * Resolution never picks an ambiguous match. Terms are matched case-insensitively and every
 * whitespace token in a term must be present somewhere in that asset's displayed metadata.
 */
public final class TradeAssetResolver {
    private final LeagueAssetInventoryAnalyzer inventory;

    public TradeAssetResolver(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.inventory = new LeagueAssetInventoryAnalyzer(database);
    }

    public ExpressionResolution resolveExpression(String leagueId, String expression) throws SQLException {
        return resolve(inventory.analyze(leagueId), expression);
    }

    public ExpressionResolution resolveExpression(String leagueId, String expression,
                                                  String source) throws SQLException {
        return resolve(inventory.analyze(leagueId, source), expression);
    }

    private ExpressionResolution resolve(LeagueAssetInventoryAnalyzer.InventoryReport report,
                                         String expression) {
        List<String> terms = parseTerms(expression);
        List<TermResolution> resolutions = new ArrayList<>();
        Set<String> selectedKeys = new LinkedHashSet<>();

        for (String term : terms) {
            TermResolution resolution = resolveTerm(report, term);
            if (resolution.status() == ResolutionStatus.RESOLVED) {
                Candidate candidate = resolution.candidates().getFirst();
                String key = candidate.type() + ":" + candidate.assetId();
                if (!selectedKeys.add(key)) {
                    throw new IllegalArgumentException("trade expression resolves the same asset more than once: "
                        + candidate.displayLabel() + " [" + candidate.assetId() + "]");
                }
            }
            resolutions.add(resolution);
        }
        return new ExpressionResolution(report.leagueId(), report.source(), expression.trim(),
            List.copyOf(resolutions));
    }

    private static TermResolution resolveTerm(LeagueAssetInventoryAnalyzer.InventoryReport report,
                                              String term) {
        String normalized = requireText(term, "asset term");
        String lower = normalized.toLowerCase(Locale.ROOT);
        AssetType forcedType = null;
        String query = normalized;
        if (lower.startsWith("player:")) {
            forcedType = AssetType.PLAYER;
            query = requireText(normalized.substring(normalized.indexOf(':') + 1), "player asset");
        } else if (lower.startsWith("pick:")) {
            forcedType = AssetType.DRAFT_PICK;
            query = requireText(normalized.substring(normalized.indexOf(':') + 1), "draft-pick asset");
        }

        List<Candidate> exactIdMatches = exactIdMatches(report, query, forcedType);
        if (exactIdMatches.size() == 1) {
            return new TermResolution(normalized, ResolutionStatus.RESOLVED, List.copyOf(exactIdMatches));
        }

        List<String> needles = tokens(query);
        List<Candidate> matches = new ArrayList<>();
        for (var team : report.teams()) {
            if (forcedType != AssetType.DRAFT_PICK) {
                for (var player : team.players()) {
                    if (matchesAll(needles, playerHaystack(team, player))) {
                        matches.add(playerCandidate(team, player));
                    }
                }
            }
            if (forcedType != AssetType.PLAYER) {
                for (var pick : team.draftPicks()) {
                    if (matchesAll(needles, pickHaystack(team, pick))) {
                        matches.add(pickCandidate(team, pick));
                    }
                }
            }
        }

        ResolutionStatus status = matches.isEmpty()
            ? ResolutionStatus.NOT_FOUND
            : matches.size() == 1 ? ResolutionStatus.RESOLVED : ResolutionStatus.AMBIGUOUS;
        return new TermResolution(normalized, status, List.copyOf(matches));
    }

    private static List<Candidate> exactIdMatches(LeagueAssetInventoryAnalyzer.InventoryReport report,
                                                  String query, AssetType forcedType) {
        List<Candidate> result = new ArrayList<>();
        for (var team : report.teams()) {
            if (forcedType != AssetType.DRAFT_PICK) {
                for (var player : team.players()) {
                    if (player.playerId().equals(query)) result.add(playerCandidate(team, player));
                }
            }
            if (forcedType != AssetType.PLAYER) {
                for (var pick : team.draftPicks()) {
                    if (pick.draftPickId().equals(query)) result.add(pickCandidate(team, pick));
                }
            }
        }
        return result;
    }

    private static Candidate playerCandidate(LeagueAssetInventoryAnalyzer.TeamInventory team,
                                             LeagueAssetInventoryAnalyzer.PlayerAsset player) {
        return new Candidate(AssetType.PLAYER, player.playerId(), player.playerName(), team.teamName(),
            null, player.position(), player.nflTeam(), player.value());
    }

    private static Candidate pickCandidate(LeagueAssetInventoryAnalyzer.TeamInventory team,
                                           LeagueAssetInventoryAnalyzer.DraftPickAsset pick) {
        return new Candidate(AssetType.DRAFT_PICK, pick.draftPickId(), pick.label(), team.teamName(),
            pick.originalTeamName(), null, null, pick.value());
    }

    private static String playerHaystack(LeagueAssetInventoryAnalyzer.TeamInventory team,
                                         LeagueAssetInventoryAnalyzer.PlayerAsset player) {
        return join(team.teamId(), team.teamName(), player.playerId(), player.playerName(),
            player.position(), player.nflTeam(), player.slot());
    }

    private static String pickHaystack(LeagueAssetInventoryAnalyzer.TeamInventory team,
                                       LeagueAssetInventoryAnalyzer.DraftPickAsset pick) {
        return join(team.teamId(), team.teamName(), pick.draftPickId(), pick.label(),
            pick.originalTeamId(), pick.originalTeamName(), Integer.toString(pick.season()),
            Integer.toString(pick.round()), pick.pickNumber() == null ? null : Integer.toString(pick.pickNumber()));
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(value.toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static boolean matchesAll(List<String> needles, String haystack) {
        for (String needle : needles) {
            if (!haystack.contains(needle)) return false;
        }
        return true;
    }

    private static List<String> tokens(String query) {
        List<String> result = new ArrayList<>();
        for (String token : requireText(query, "asset query").toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isBlank()) result.add(token);
        }
        return List.copyOf(result);
    }

    private static List<String> parseTerms(String expression) {
        String normalized = requireText(expression, "trade expression");
        List<String> result = new ArrayList<>();
        for (String raw : normalized.split("\\s*(?:\\+|,)\\s*", -1)) {
            String term = raw.trim();
            if (term.isEmpty()) throw new IllegalArgumentException("trade expression contains a blank asset term");
            result.add(term);
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum AssetType { PLAYER, DRAFT_PICK }
    public enum ResolutionStatus { RESOLVED, NOT_FOUND, AMBIGUOUS }

    public record Candidate(AssetType type, String assetId, String displayLabel,
                            String ownerTeamName, String originalTeamName,
                            String position, String nflTeam, Double value) {
        public String description() {
            if (type == AssetType.PLAYER) {
                String nfl = nflTeam == null ? "" : " " + nflTeam;
                return "PLAYER " + position + " " + displayLabel + nfl + " team=" + ownerTeamName
                    + " [" + assetId + "]";
            }
            String original = originalTeamName == null || originalTeamName.equals(ownerTeamName)
                ? "" : " original=" + originalTeamName;
            return "PICK " + displayLabel + " team=" + ownerTeamName + original + " [" + assetId + "]";
        }
    }

    public record TermResolution(String term, ResolutionStatus status, List<Candidate> candidates) {
        public boolean resolved() { return status == ResolutionStatus.RESOLVED; }
        public Candidate resolvedCandidate() { return resolved() ? candidates.getFirst() : null; }
    }

    public record ExpressionResolution(String leagueId, String source, String expression,
                                       List<TermResolution> terms) {
        public boolean complete() { return terms.stream().allMatch(TermResolution::resolved); }
        public int unresolvedTerms() { return (int) terms.stream().filter(term -> !term.resolved()).count(); }

        public TradeAssetAnalyzer.TradePackage tradePackage() {
            if (!complete()) {
                throw new IllegalArgumentException(failureMessage());
            }
            List<String> players = new ArrayList<>();
            List<String> picks = new ArrayList<>();
            for (TermResolution term : terms) {
                Candidate candidate = term.resolvedCandidate();
                if (candidate.type() == AssetType.PLAYER) players.add(candidate.assetId());
                else picks.add(candidate.assetId());
            }
            return new TradeAssetAnalyzer.TradePackage(List.copyOf(players), List.copyOf(picks));
        }

        public String failureMessage() {
            List<String> failures = new ArrayList<>();
            for (TermResolution term : terms) {
                if (term.status() == ResolutionStatus.NOT_FOUND) {
                    failures.add("no league asset matched '" + term.term() + "'");
                } else if (term.status() == ResolutionStatus.AMBIGUOUS) {
                    String candidates = term.candidates().stream()
                        .map(Candidate::description)
                        .collect(java.util.stream.Collectors.joining("; "));
                    failures.add("ambiguous asset '" + term.term() + "': " + candidates);
                }
            }
            return String.join(" | ", failures);
        }
    }
}
