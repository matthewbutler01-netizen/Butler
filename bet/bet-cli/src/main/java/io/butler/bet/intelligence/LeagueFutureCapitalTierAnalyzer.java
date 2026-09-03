package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies the governed league-relative future-capital policy to persisted draft-pick market value.
 * Draft seasons remain descriptive context; no current-roster posture or recommendation is changed.
 */
public final class LeagueFutureCapitalTierAnalyzer {
    private final LeagueDraftCapitalTimelineAnalyzer draftCapital;

    public LeagueFutureCapitalTierAnalyzer(Database database) {
        this.draftCapital = new LeagueDraftCapitalTimelineAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public FutureCapitalReport analyze(String leagueId) throws SQLException {
        return classify(draftCapital.analyze(leagueId));
    }

    public FutureCapitalReport analyze(String leagueId, String source) throws SQLException {
        return classify(draftCapital.analyze(leagueId, source));
    }

    public FutureCapitalReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return classify(draftCapital.analyze(leagueId, minimumAsOfDate));
    }

    public FutureCapitalReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        return classify(draftCapital.analyze(leagueId, source, minimumAsOfDate));
    }

    public static FutureCapitalReport classify(LeagueDraftCapitalTimelineAnalyzer.DraftCapitalReport draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        if (draft.teams().size() < LeagueFutureCapitalTierPolicy.MINIMUM_LEAGUE_TEAMS) {
            return insufficient(draft, "At least four league teams are required for relative future-capital tiers.");
        }
        if (draft.totalPicks() == 0) {
            return insufficient(draft, "At least one persisted future draft pick is required for future-capital tiers.");
        }
        if (draft.missingPicks() > 0 || draft.stalePicks() > 0 || draft.valuedPicks() != draft.totalPicks()) {
            return insufficient(draft, "Complete usable draft-pick value coverage is required across the league.");
        }

        List<LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital> ranked = new ArrayList<>(draft.teams());
        ranked.sort(Comparator.comparingDouble(LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital::value).reversed()
            .thenComparing(LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital::teamId));

        int outerSize = LeagueFutureCapitalTierPolicy.outerTierSize(ranked.size());
        double highBoundary = ranked.get(outerSize - 1).value();
        double lowBoundary = ranked.get(ranked.size() - outerSize).value();
        boolean collapsed = Double.compare(highBoundary, lowBoundary) == 0;

        Map<String, LeagueFutureCapitalTierPolicy.Tier> tiers = new HashMap<>();
        for (var team : ranked) {
            LeagueFutureCapitalTierPolicy.Tier tier;
            if (collapsed) {
                tier = LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL;
            } else if (Double.compare(team.value(), highBoundary) >= 0) {
                tier = LeagueFutureCapitalTierPolicy.Tier.HIGH_FUTURE_CAPITAL;
            } else if (Double.compare(team.value(), lowBoundary) <= 0) {
                tier = LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL;
            } else {
                tier = LeagueFutureCapitalTierPolicy.Tier.MIDDLE_FUTURE_CAPITAL;
            }
            tiers.put(team.teamId(), tier);
        }

        List<TeamFutureCapital> teams = draft.teams().stream()
            .map(team -> freeze(team, tiers.get(team.teamId())))
            .sorted(Comparator.comparing(TeamFutureCapital::teamName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamFutureCapital::teamId))
            .toList();
        return new FutureCapitalReport(draft.leagueId(), draft.source(), draft.minimumAsOfDate(),
            LeagueFutureCapitalTierPolicy.POLICY_ID, true, null, teams);
    }

    private static FutureCapitalReport insufficient(LeagueDraftCapitalTimelineAnalyzer.DraftCapitalReport draft,
                                                    String reason) {
        List<TeamFutureCapital> teams = draft.teams().stream()
            .map(team -> freeze(team, LeagueFutureCapitalTierPolicy.Tier.INSUFFICIENT_EVIDENCE))
            .toList();
        return new FutureCapitalReport(draft.leagueId(), draft.source(), draft.minimumAsOfDate(),
            LeagueFutureCapitalTierPolicy.POLICY_ID, false, reason, teams);
    }

    private static TeamFutureCapital freeze(LeagueDraftCapitalTimelineAnalyzer.TeamDraftCapital team,
                                            LeagueFutureCapitalTierPolicy.Tier tier) {
        return new TeamFutureCapital(team.teamId(), team.teamName(), team.value(), team.valuedPicks(),
            team.stalePicks(), team.missingPicks(), team.totalPicks(), team.seasons(), tier);
    }

    public record TeamFutureCapital(String teamId, String teamName, double value, int valuedPicks,
                                    int stalePicks, int missingPicks, int totalPicks,
                                    List<LeagueDraftCapitalTimelineAnalyzer.SeasonDraftCapital> seasons,
                                    LeagueFutureCapitalTierPolicy.Tier tier) {
        public TeamFutureCapital {
            if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
            if (teamName == null || teamName.isBlank()) throw new IllegalArgumentException("teamName must not be blank");
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("value must be finite and non-negative");
            if (valuedPicks < 0 || stalePicks < 0 || missingPicks < 0 || totalPicks < 0) {
                throw new IllegalArgumentException("pick counts must not be negative");
            }
            seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons must not be null"));
            Objects.requireNonNull(tier, "tier must not be null");
        }

        public double coveragePercent() {
            return totalPicks == 0 ? 100.0 : valuedPicks * 100.0 / totalPicks;
        }
    }

    public record FutureCapitalReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                      String policyId, boolean available, String insufficiencyReason,
                                      List<TeamFutureCapital> teams) {
        public FutureCapitalReport {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            if (!LeagueFutureCapitalTierPolicy.POLICY_ID.equals(policyId)) {
                throw new IllegalArgumentException("unexpected policyId");
            }
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && insufficiencyReason != null) {
                throw new IllegalArgumentException("available report cannot have insufficiencyReason");
            }
            if (!available && (insufficiencyReason == null || insufficiencyReason.isBlank())) {
                throw new IllegalArgumentException("unavailable report requires insufficiencyReason");
            }
        }
    }
}
