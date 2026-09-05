package io.butler.bet.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionClaimDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void immutableExecutionClaimRejectsDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf444-execution-claim-delete.db"));
        database.initialize();
        new TradeCounterExecutionClaimRepository(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_trade_counter_execution_claim_matching_ready_intent");

            assertEquals(1, statement.executeUpdate("""
                INSERT INTO trade_counter_execution_claims(
                    claim_id, claim_policy_id, attempt_id, grant_id, readiness_policy_id,
                    authorization_policy_id, proposal_fingerprint, fresh_fingerprint,
                    action, destination_type, destination_id, claimed_at)
                VALUES (
                    'claim-delete-test',
                    'trade-counter-execution-claim-v1-ready-active-prepared-atomic',
                    'attempt-delete-test', 'grant-delete-test',
                    'trade-counter-execution-readiness-v1-trusted-grant-fresh-replay-no-consume',
                    'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'SUBMIT_COUNTER_TRADE', 'LEAGUE', 'league-delete-test',
                    '2026-09-05T02:00:00Z')
                """));

            var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM trade_counter_execution_claims WHERE claim_id='claim-delete-test'"));
            assertTrue(error.getMessage().contains("execution claim is immutable"));

            try (var rs = statement.executeQuery(
                "SELECT claim_id FROM trade_counter_execution_claims WHERE claim_id='claim-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("claim-delete-test", rs.getString(1));
            }
        }
    }
}
