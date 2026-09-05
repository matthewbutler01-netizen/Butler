package io.butler.bet.data;

import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperManualMessageOutcomeCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterManualTerminalOutcomeDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void allManualTerminalOutcomeTablesRejectDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf442-terminal-delete.db"));
        database.initialize();
        new SleeperCounterTradeOutcomeCoordinator(database).initialize();
        new SleeperManualMessageOutcomeCoordinator(database).initialize();
        new SleeperManualCounterNoActionOutcomeCoordinator(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_counter_trade_terminal_outcome_trusted");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_manual_message_terminal_outcome_trusted");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_manual_no_action_terminal_outcome_trusted");

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_counter_trade_terminal_outcomes(
                    outcome_id, coordinator_policy_id, evidence_policy_id,
                    reconciliation_service_id, reconciliation_policy_id,
                    claim_id, handoff_id, attempt_id, grant_id, movement_sha256,
                    sleeper_week, sleeper_transaction_id, terminal_state,
                    grant_disposition, evidence_reason, applied_at)
                VALUES (
                    'trade-outcome-delete-test',
                    'sleeper-counter-trade-outcome-coordinator-v1-exact-complete-atomic-success-consume',
                    'sleeper-counter-trade-reconciliation-outcome-v1-complete-only-success-no-negative-inference',
                    'sleeper-counter-trade-snapshot-reconciliation-v1-explicit-week-read-only',
                    'sleeper-trade-reconciliation-v1-exact-assets-rosters-created-after',
                    'trade-claim-delete-test', 'trade-handoff-delete-test',
                    'trade-attempt-delete-test', 'trade-grant-delete-test',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    7, 'tx-delete-test', 'SUCCEEDED', 'CONSUME',
                    'Trade terminal delete-guard fixture.', '2026-09-05T02:00:00Z')
                """));

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_manual_message_terminal_outcomes(
                    outcome_id, coordinator_policy_id, acknowledgment_journal_policy_id,
                    acknowledgment_policy_id, acknowledgment_id, claim_id, attempt_id, grant_id,
                    handoff_id, payload_sha256, destination_id, confirmation, acknowledged_at,
                    terminal_state, grant_disposition, evidence_reason, applied_at)
                VALUES (
                    'message-outcome-delete-test',
                    'sleeper-manual-message-outcome-coordinator-v1-durable-ack-atomic-success-consume',
                    'sleeper-manual-message-acknowledgment-journal-v1-exact-active-handoff-immutable',
                    'sleeper-manual-message-acknowledgment-v1-explicit-handoff-payload-confirmation',
                    'message-ack-delete-test', 'message-claim-delete-test',
                    'message-attempt-delete-test', 'message-grant-delete-test',
                    'message-handoff-delete-test',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'manager-delete-test', 'SENT_EXACT_MESSAGE', '2026-09-05T02:00:00Z',
                    'SUCCEEDED', 'CONSUME', 'Message terminal delete-guard fixture.',
                    '2026-09-05T02:00:01Z')
                """));

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_manual_counter_no_action_terminal_outcomes(
                    outcome_id, coordinator_policy_id, acknowledgment_journal_policy_id,
                    acknowledgment_policy_id, acknowledgment_id, claim_id, attempt_id, grant_id,
                    handoff_id, payload_sha256, action, destination_type, destination_id,
                    confirmation, acknowledged_at, terminal_state, grant_disposition,
                    evidence_reason, applied_at)
                VALUES (
                    'no-action-outcome-delete-test',
                    'sleeper-manual-counter-no-action-outcome-coordinator-v1-durable-ack-atomic-failed-consume',
                    'sleeper-manual-counter-no-action-acknowledgment-journal-v1-exact-active-handoff-immutable',
                    'sleeper-manual-counter-no-action-acknowledgment-v1-explicit-handoff-payload-confirmation',
                    'no-action-ack-delete-test', 'no-action-claim-delete-test',
                    'no-action-attempt-delete-test', 'no-action-grant-delete-test',
                    'no-action-handoff-delete-test',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                    'SUBMIT_COUNTER_TRADE', 'LEAGUE', 'league-delete-test',
                    'NO_EXTERNAL_ACTION_TAKEN', '2026-09-05T02:00:00Z',
                    'FAILED', 'CONSUME', 'No-action terminal delete-guard fixture.',
                    '2026-09-05T02:00:01Z')
                """));

            assertDeleteRejected(statement,
                "sleeper_counter_trade_terminal_outcomes", "trade-outcome-delete-test",
                "manual trade terminal outcome is immutable");
            assertDeleteRejected(statement,
                "sleeper_manual_message_terminal_outcomes", "message-outcome-delete-test",
                "manual message terminal outcome is immutable");
            assertDeleteRejected(statement,
                "sleeper_manual_counter_no_action_terminal_outcomes", "no-action-outcome-delete-test",
                "manual no-action terminal outcome is immutable");
        }
    }

    private static void assertDeleteRejected(
        java.sql.Statement statement,
        String table,
        String outcomeId,
        String expectedMessage) throws Exception {
        var error = assertThrows(java.sql.SQLException.class,
            () -> statement.executeUpdate("DELETE FROM " + table + " WHERE outcome_id='" + outcomeId + "'"));
        assertTrue(error.getMessage().contains(expectedMessage));
        try (var rs = statement.executeQuery(
            "SELECT outcome_id FROM " + table + " WHERE outcome_id='" + outcomeId + "'")) {
            assertTrue(rs.next());
            assertEquals(outcomeId, rs.getString(1));
        }
    }
}
