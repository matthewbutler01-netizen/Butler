package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperManualCounterHandoffDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void immutableManualHandoffJournalRejectsDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf440-handoff-delete.db"));
        database.initialize();
        new SleeperManualCounterHandoffRepository(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_manual_counter_handoff_trusted_request");
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_manual_counter_handoffs(
                    handoff_id, journal_policy_id, handoff_service_id, capability_policy_id,
                    execution_request_policy_id, claim_id, attempt_id, grant_id,
                    proposal_fingerprint, action, destination_type, destination_id,
                    payload_kind, payload_sha256, reconciliation_mode, presented_at)
                VALUES (
                    'handoff-delete-test',
                    'sleeper-manual-counter-handoff-journal-v1-first-presentation-immutable',
                    'sleeper-manual-counter-handoff-v1-trusted-claim-present-only',
                    'sleeper-platform-capability-v1-official-read-only-manual-write-handoff',
                    'trade-counter-execution-request-v1-persisted-claim-attempt-only',
                    'claim-delete-test', 'attempt-delete-test', 'grant-delete-test',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'SUBMIT_COUNTER_TRADE', 'LEAGUE', 'league-delete-test',
                    'COUNTER_TRADE_REQUEST_JSON',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'SLEEPER_TRANSACTION_READBACK', '2026-09-05T02:00:00Z')
                """));

            var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM sleeper_manual_counter_handoffs WHERE handoff_id='handoff-delete-test'"));
            assertTrue(error.getMessage().contains("manual handoff presentation record is immutable"));

            try (var rs = statement.executeQuery(
                "SELECT handoff_id FROM sleeper_manual_counter_handoffs"
                    + " WHERE handoff_id='handoff-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("handoff-delete-test", rs.getString(1));
            }
        }
    }
}
