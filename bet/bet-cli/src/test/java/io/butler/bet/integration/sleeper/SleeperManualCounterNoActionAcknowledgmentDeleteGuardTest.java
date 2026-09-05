package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperManualCounterNoActionAcknowledgmentDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void immutableNoActionAcknowledgmentJournalRejectsDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf439-no-action-ack-delete.db"));
        database.initialize();
        new SleeperManualCounterNoActionAcknowledgmentRepository(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_manual_no_action_trusted_active_handoff");
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_manual_counter_no_action_acknowledgments(
                    acknowledgment_id, journal_policy_id, acknowledgment_policy_id,
                    handoff_journal_policy_id, handoff_service_id, claim_id, attempt_id,
                    grant_id, handoff_id, payload_sha256, action, destination_type, destination_id,
                    confirmation, terminal_eligibility, attempt_terminal_state, grant_disposition,
                    presented_at, acknowledged_at, evidence_reason, recorded_at)
                VALUES (
                    'no-action-delete-test',
                    'sleeper-manual-counter-no-action-acknowledgment-journal-v1-exact-active-handoff-immutable',
                    'sleeper-manual-counter-no-action-acknowledgment-v1-explicit-handoff-payload-confirmation',
                    'sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable',
                    'sleeper-manual-counter-handoff-v1-trusted-claim-present-only',
                    'claim-delete-test', 'attempt-delete-test', 'grant-delete-test', 'handoff-delete-test',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'SUBMIT_COUNTER_TRADE', 'LEAGUE', 'league-delete-test',
                    'NO_EXTERNAL_ACTION_TAKEN', 'CONFIRMED_NO_ACTION_FAILURE', 'FAILED', 'CONSUME',
                    '2026-09-05T02:00:00Z', '2026-09-05T02:00:01Z',
                    'Exact no-action acknowledgment delete-guard fixture.', '2026-09-05T02:00:02Z')
                """));

            var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM sleeper_manual_counter_no_action_acknowledgments"
                    + " WHERE acknowledgment_id='no-action-delete-test'"));
            assertTrue(error.getMessage().contains("manual no-action acknowledgment is immutable"));

            try (var rs = statement.executeQuery(
                "SELECT acknowledgment_id FROM sleeper_manual_counter_no_action_acknowledgments"
                    + " WHERE acknowledgment_id='no-action-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("no-action-delete-test", rs.getString(1));
            }
        }
    }
}
