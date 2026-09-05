package io.butler.bet.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionOutcomeDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void executionOutcomeAndUnknownResolutionRejectDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf443-execution-provenance-delete.db"));
        database.initialize();
        new TradeCounterExecutionOutcomeCoordinator(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_trade_counter_execution_outcome_matching_in_flight");
            statement.executeUpdate("DROP TRIGGER trg_trade_counter_execution_unknown_resolution_matching");

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO trade_counter_execution_outcomes(
                    outcome_id, coordinator_policy_id, outcome_policy_id,
                    claim_id, attempt_id, grant_id, payload_sha256,
                    executor_id, executor_mode, executor_state, outcome_state,
                    attempt_terminal_state, grant_disposition, reconciliation_required,
                    executor_detail, reason, applied_at)
                VALUES (
                    'outcome-delete-test',
                    'trade-counter-execution-outcome-coordinator-v1-atomic-terminal-consume-unknown-lock',
                    'trade-counter-execution-outcome-v1-live-terminal-no-retry-unknown-reconcile',
                    'claim-delete-test', 'attempt-delete-test', 'grant-delete-test',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'executor-delete-test', 'LIVE', 'DISPATCHED', 'CONFIRMED_SUCCESS',
                    'SUCCEEDED', 'CONSUME', 0,
                    'Execution outcome delete-guard fixture.',
                    'Exact success evidence fixture.', '2026-09-05T02:00:00Z')
                """));

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO trade_counter_execution_unknown_resolutions(
                    resolution_id, coordinator_policy_id, outcome_policy_id,
                    outcome_id, claim_id, attempt_id, grant_id,
                    resolution, grant_disposition, remote_action_confirmed,
                    evidence_detail, reason, resolved_at)
                VALUES (
                    'resolution-delete-test',
                    'trade-counter-execution-outcome-coordinator-v1-atomic-terminal-consume-unknown-lock',
                    'trade-counter-execution-outcome-v1-live-terminal-no-retry-unknown-reconcile',
                    'outcome-delete-test', 'resolution-claim-delete-test',
                    'resolution-attempt-delete-test', 'resolution-grant-delete-test',
                    'REMOTE_ACTION_CONFIRMED', 'CONSUME', 1,
                    'UNKNOWN resolution delete-guard fixture.',
                    'Exact reconciliation fixture.', '2026-09-05T02:00:01Z')
                """));

            var outcomeError = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM trade_counter_execution_outcomes WHERE outcome_id='outcome-delete-test'"));
            assertTrue(outcomeError.getMessage().contains("execution outcome is immutable"));

            var resolutionError = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM trade_counter_execution_unknown_resolutions"
                    + " WHERE resolution_id='resolution-delete-test'"));
            assertTrue(resolutionError.getMessage().contains("UNKNOWN resolution is immutable"));

            try (var rs = statement.executeQuery(
                "SELECT outcome_id FROM trade_counter_execution_outcomes"
                    + " WHERE outcome_id='outcome-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("outcome-delete-test", rs.getString(1));
            }
            try (var rs = statement.executeQuery(
                "SELECT resolution_id FROM trade_counter_execution_unknown_resolutions"
                    + " WHERE resolution_id='resolution-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("resolution-delete-test", rs.getString(1));
            }
        }
    }
}
