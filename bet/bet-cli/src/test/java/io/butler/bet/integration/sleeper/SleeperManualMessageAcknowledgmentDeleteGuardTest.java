package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperManualMessageAcknowledgmentDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void immutableMessageAcknowledgmentJournalRejectsDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf438-message-ack-delete.db"));
        database.initialize();
        new SleeperManualMessageAcknowledgmentRepository(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_manual_message_ack_trusted_active_handoff");
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_manual_message_acknowledgments(
                    acknowledgment_id, journal_policy_id, acknowledgment_policy_id,
                    handoff_journal_policy_id, handoff_service_id, claim_id, attempt_id,
                    grant_id, handoff_id, payload_sha256, destination_id, confirmation,
                    completion_eligibility, presented_at, acknowledged_at, evidence_reason, recorded_at)
                VALUES (
                    'ack-delete-test',
                    'sleeper-manual-message-acknowledgment-journal-v1-exact-active-handoff-immutable',
                    'sleeper-manual-message-acknowledgment-v1-explicit-handoff-payload-confirmation',
                    'sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable',
                    'sleeper-manual-counter-handoff-v1-trusted-claim-present-only',
                    'claim-delete-test', 'attempt-delete-test', 'grant-delete-test', 'handoff-delete-test',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'manager-delete-test', 'SENT_EXACT_MESSAGE', 'MANUAL_MESSAGE_SUCCESS',
                    '2026-09-05T02:00:00Z', '2026-09-05T02:00:01Z',
                    'Exact message acknowledgment delete-guard fixture.', '2026-09-05T02:00:02Z')
                """));

            var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM sleeper_manual_message_acknowledgments WHERE acknowledgment_id='ack-delete-test'"));
            assertTrue(error.getMessage().contains("manual message acknowledgment is immutable"));

            try (var rs = statement.executeQuery(
                "SELECT acknowledgment_id FROM sleeper_manual_message_acknowledgments"
                    + " WHERE acknowledgment_id='ack-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("ack-delete-test", rs.getString(1));
            }
        }
    }
}
