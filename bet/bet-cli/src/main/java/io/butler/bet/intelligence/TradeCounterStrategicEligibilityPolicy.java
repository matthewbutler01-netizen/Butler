package io.butler.bet.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Derives the strategically eligible subset from season-aware counter candidate vetting.
 * CLEAR candidates remain eligible; BLOCKED candidates are excluded. Market ordering is preserved
 * exactly and no candidate is selected or re-ranked.
 */
public final class TradeCounterStrategicEligibilityPolicy {
    public static final String POLICY_ID =
        "trade-counter-strategic-eligibility-v1-clear-only-preserve-market-rank";

    private TradeCounterStrategicEligibilityPolicy() {}

    public static EligibilityReport classify(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report) {
        Objects.requireNonNull(report, "report must not be null");
        if (!report.available()) {
            return new EligibilityReport(
                POLICY_ID,
                report.policyId(),
                report.leagueId(),
                report.season(),
                report.source(),
                report.minimumAsOfDate(),
                false,
                report.insufficiencyReason(),
                List.of(),
                List.of());
        }

        var eligible = report.candidates().stream()
            .filter(candidate -> candidate.state()
                == TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR)
            .toList();
        var blocked = report.candidates().stream()
            .filter(candidate -> candidate.state()
                == TradeCounterStrategicCandidateVettingAnalyzer.VettingState.BLOCKED)
            .toList();
        return new EligibilityReport(
            POLICY_ID,
            report.policyId(),
            report.leagueId(),
            report.season(),
            report.source(),
            report.minimumAsOfDate(),
            true,
            null,
            eligible,
            blocked);
    }

    public record EligibilityReport(
        String policyId,
        String strategicVettingPolicyId,
        String leagueId,
        int season,
        String source,
        java.time.LocalDate minimumAsOfDate,
        boolean available,
        String insufficiencyReason,
        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> eligibleCandidates,
        List<TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidate> blockedCandidates) {
        public EligibilityReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID.equals(strategicVettingPolicyId)) {
                throw new IllegalArgumentException("unexpected strategicVettingPolicyId");
            }
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            eligibleCandidates = List.copyOf(Objects.requireNonNull(eligibleCandidates, "eligibleCandidates must not be null"));
            blockedCandidates = List.copyOf(Objects.requireNonNull(blockedCandidates, "blockedCandidates must not be null"));
            if (available) {
                if (insufficiencyReason != null) {
                    throw new IllegalArgumentException("available eligibility report cannot carry insufficiency reason");
                }
                if (eligibleCandidates.stream().anyMatch(candidate -> candidate.state()
                    != TradeCounterStrategicCandidateVettingAnalyzer.VettingState.CLEAR)) {
                    throw new IllegalArgumentException("eligibleCandidates must contain only CLEAR candidates");
                }
                if (blockedCandidates.stream().anyMatch(candidate -> candidate.state()
                    != TradeCounterStrategicCandidateVettingAnalyzer.VettingState.BLOCKED)) {
                    throw new IllegalArgumentException("blockedCandidates must contain only BLOCKED candidates");
                }
            } else {
                if (insufficiencyReason == null || insufficiencyReason.isBlank()) {
                    throw new IllegalArgumentException("unavailable eligibility report requires insufficiency reason");
                }
                if (!eligibleCandidates.isEmpty() || !blockedCandidates.isEmpty()) {
                    throw new IllegalArgumentException("unavailable eligibility report cannot carry candidates");
                }
            }
        }
    }
}
