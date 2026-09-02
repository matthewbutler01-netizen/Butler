package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Describes how much of each team's usable asset value is concentrated in its highest-valued
 * players and draft picks. This is descriptive context only; no preferred concentration level,
 * roster strategy, or risk label is inferred.
 */
public final class LeagueAssetConcentrationAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository playerValues;
    private final DraftPickRepository draftPicks;
    private final DraftPickValueRepository draftPickValues;

    public LeagueAssetConcentrationAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.playerValues = new PlayerValueRepository(database);
        this.draftPicks = new DraftPickRepository(database);
        this.draftPickValues = new DraftPickValueRepository(database);
    }

    public ConcentrationReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), null);
    }

    public ConcentrationReport analyze(String leagueId, String source) throws SQLException {
        return analyze(leagueId, source, null);
    }

    public ConcentrationReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), minimumAsOfDate);
    }

    public ConcentrationReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var league = leagues.analyze(normalizedLeagueId);
        List<TeamConcentration> teams = new ArrayList<>();

        for (var team : league.teams()) {
            List<AssetValue> assets = new ArrayList<>();
            int totalAssets = 0;
            int missingAssets = 0;
            int staleAssets = 0;

            for (var roster : rosters.findByTeamId(team.teamId())) {
                totalAssets++;
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalArgumentException("player not found: " + roster.getPlayerId()));
                var value = playerValues.findLatestByPlayerIdAndSource(player.getId(), normalizedSource).orElse(null);
                if (value == null) {
                    missingAssets++;
                    continue;
                }
                if (minimumAsOfDate != null && value.getAsOfDate().isBefore(minimumAsOfDate)) {
                    staleAssets++;
                    continue;
                }
                assets.add(new AssetValue(AssetType.PLAYER, player.getId(), player.getDisplayName(),
                    value.getValue(), value.getAsOfDate()));
            }

            for (var pick : draftPicks.findByOwnerTeamId(team.teamId())) {
                totalAssets++;
                var value = draftPickValues.findLatestByDraftPickIdAndSource(pick.getId(), normalizedSource).orElse(null);
                if (value == null) {
                    missingAssets++;
                    continue;
                }
                if (minimumAsOfDate != null && value.getAsOfDate().isBefore(minimumAsOfDate)) {
                    staleAssets++;
                    continue;
                }
                String label = pick.getSeason() + " R" + pick.getRound()
                    + (pick.getPickNumber() == null ? "" : " #" + pick.getPickNumber());
                assets.add(new AssetValue(AssetType.DRAFT_PICK, pick.getId(), label,
                    value.getValue(), value.getAsOfDate()));
            }

            assets.sort(Comparator.comparingDouble(AssetValue::value).reversed()
                .thenComparing(AssetValue::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AssetValue::assetId));
            double totalValue = assets.stream().mapToDouble(AssetValue::value).sum();
            teams.add(new TeamConcentration(team.teamId(), team.teamName(), normalizedSource,
                minimumAsOfDate, totalValue, totalAssets, assets.size(), staleAssets, missingAssets,
                List.copyOf(assets)));
        }

        teams.sort(Comparator.comparing(TeamConcentration::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamConcentration::teamId));
        return new ConcentrationReport(normalizedLeagueId, normalizedSource, minimumAsOfDate, List.copyOf(teams));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum AssetType { PLAYER, DRAFT_PICK }

    public record AssetValue(AssetType type, String assetId, String label, double value, LocalDate asOfDate) {}

    public record TeamConcentration(String teamId, String teamName, String source, LocalDate minimumAsOfDate,
                                    double usableAssetValue, int totalAssets, int valuedAssets,
                                    int staleAssets, int missingAssets, List<AssetValue> assets) {
        public TeamConcentration {
            assets = List.copyOf(Objects.requireNonNull(assets, "assets must not be null"));
        }

        public double coveragePercent() {
            return totalAssets == 0 ? 0.0 : valuedAssets * 100.0 / totalAssets;
        }

        public double topAssetSharePercent() { return shareOfTop(1); }
        public double topThreeSharePercent() { return shareOfTop(3); }
        public double topFiveSharePercent() { return shareOfTop(5); }

        public double herfindahlIndex() {
            if (usableAssetValue <= 0.0) return 0.0;
            double sum = 0.0;
            for (AssetValue asset : assets) {
                double share = asset.value() / usableAssetValue;
                sum += share * share;
            }
            return sum;
        }

        public List<AssetValue> topAssets(int limit) {
            if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
            return assets.subList(0, Math.min(limit, assets.size()));
        }

        private double shareOfTop(int count) {
            if (usableAssetValue <= 0.0) return 0.0;
            return topAssets(count).stream().mapToDouble(AssetValue::value).sum() * 100.0 / usableAssetValue;
        }
    }

    public record ConcentrationReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                      List<TeamConcentration> teams) {
        public ConcentrationReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
    }
}
