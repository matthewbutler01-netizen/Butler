package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.LeagueRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Determines whether every persisted provider lineup slot is governed by an explicit policy. */
public final class LeagueLineupEligibilityCoverageAnalyzer {
    public static final String POLICY_ID =
        "league-lineup-eligibility-coverage-v1-explicit-slots-fail-closed";

    private final Database database;
    private final LineupSlotEligibilityPolicy eligibilityPolicy = new LineupSlotEligibilityPolicy();

    public LeagueLineupEligibilityCoverageAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CoverageReport analyze(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId").trim();
        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var slots = new LeagueLineupConfigurationRepository(database).findByLeagueId(normalizedLeagueId);

        if (slots.isEmpty()) {
            return new CoverageReport(
                POLICY_ID,
                LineupSlotEligibilityPolicy.POLICY_ID,
                league.getId(),
                league.getName(),
                CoverageState.NO_LINEUP_CONFIGURATION,
                List.of(),
                0,
                0,
                0,
                "No persisted provider lineup configuration is available; legal lineup eligibility cannot be established.");
        }

        List<SlotCoverage> coverage = new ArrayList<>();
        int supportedStarting = 0;
        int nonStarting = 0;
        int unsupported = 0;
        for (int ordinal = 0; ordinal < slots.size(); ordinal++) {
            var rule = eligibilityPolicy.ruleFor(slots.get(ordinal));
            switch (rule.state()) {
                case STARTING_SUPPORTED -> supportedStarting++;
                case NON_STARTING -> nonStarting++;
                case UNSUPPORTED -> unsupported++;
            }
            coverage.add(new SlotCoverage(
                ordinal,
                rule.slot(),
                rule.state(),
                rule.eligibleFantasyPositions()));
        }

        CoverageState state;
        String reason;
        if (unsupported > 0) {
            state = CoverageState.INCOMPLETE;
            reason = "At least one provider lineup slot has no explicit Butler eligibility rule.";
        } else if (supportedStarting == 0) {
            state = CoverageState.NO_STARTING_SLOTS;
            reason = "The provider lineup configuration contains no supported starting slots.";
        } else {
            state = CoverageState.COMPLETE;
            reason = "Every provider lineup slot is explicitly governed as a supported starter or non-starting slot.";
        }

        return new CoverageReport(
            POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            league.getId(),
            league.getName(),
            state,
            List.copyOf(coverage),
            supportedStarting,
            nonStarting,
            unsupported,
            reason);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum CoverageState {
        COMPLETE,
        INCOMPLETE,
        NO_LINEUP_CONFIGURATION,
        NO_STARTING_SLOTS
    }

    public record SlotCoverage(
        int ordinal,
        String slot,
        LineupSlotEligibilityPolicy.SlotState state,
        List<String> eligibleFantasyPositions) {
        public SlotCoverage {
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
            requireText(slot, "slot");
            Objects.requireNonNull(state, "state must not be null");
            eligibleFantasyPositions = List.copyOf(Objects.requireNonNull(
                eligibleFantasyPositions, "eligibleFantasyPositions must not be null"));
        }
    }

    public record CoverageReport(
        String policyId,
        String eligibilityPolicyId,
        String leagueId,
        String leagueName,
        CoverageState state,
        List<SlotCoverage> slots,
        int supportedStartingSlots,
        int nonStartingSlots,
        int unsupportedSlots,
        String reason) {
        public CoverageReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!LineupSlotEligibilityPolicy.POLICY_ID.equals(eligibilityPolicyId)) {
                throw new IllegalArgumentException("unexpected eligibilityPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            Objects.requireNonNull(state, "state must not be null");
            slots = List.copyOf(Objects.requireNonNull(slots, "slots must not be null"));
            if (supportedStartingSlots < 0 || nonStartingSlots < 0 || unsupportedSlots < 0) {
                throw new IllegalArgumentException("slot counts must not be negative");
            }
            if (slots.size() != supportedStartingSlots + nonStartingSlots + unsupportedSlots) {
                throw new IllegalArgumentException("slot counts must match slots");
            }
            requireText(reason, "reason");
        }

        public boolean legalLineupEligible() {
            return state == CoverageState.COMPLETE;
        }
    }
}
