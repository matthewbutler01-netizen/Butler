package io.butler.bet.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterLifecycleRetentionDeleteGuardTest {
    private static final String AUTH_POLICY =
        "trade-counter-authorization-v1-explicit-fingerprint-action-destination-once";
    private static final String ATTEMPT_POLICY =
        "trade-counter-execution-attempt-journal-v1-durable-bound-payload-state-machine";
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HELLO_SHA256 =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    Path tempDir;

    @Test
    void attemptsAndGrantsAreRetainedWhileLifecycleUpdatesRemainAllowed() throws Exception {
        Database database = new Database(tempDir.resolve("bf445-retention.db"));
        database.initialize();
        new TradeCounterExecutionAttemptRepository(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO trade_counter_authorization_grants(
                    grant_id, policy_id, granted_at, league_id, season, source,
                    minimum_as_of_date, perspective, proposal_fingerprint, action,
                    destination_type, destination_id, max_uses, consumed_at)
                VALUES(
                    'grant-attempt', 'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once',
                    '2026-09-05T00:00:00Z', 'league-1', 2026, 'test', NULL, 'TEST',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'SEND_NEGOTIATION_MESSAGE', 'MANAGER', 'manager-1', 1, NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO trade_counter_authorization_grants(
                    grant_id, policy_id, granted_at, league_id, season, source,
                    minimum_as_of_date, perspective, proposal_fingerprint, action,
                    destination_type, destination_id, max_uses, consumed_at)
                VALUES(
                    'grant-only', 'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once',
                    '2026-09-05T00:00:00Z', 'league-1', 2026, 'test', NULL, 'TEST',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'SEND_NEGOTIATION_MESSAGE', 'MANAGER', 'manager-2', 1, NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO trade_counter_execution_attempts(
                    attempt_id, journal_policy_id, grant_id, authorization_policy_id,
                    proposal_fingerprint, action, destination_type, destination_id,
                    payload_kind, payload_text, payload_sha256, state, prepared_at,
                    in_flight_at, terminal_at, outcome_detail, updated_at)
                VALUES(
                    'attempt-1', 'trade-counter-execution-attempt-journal-v1-durable-bound-payload-state-machine',
                    'grant-attempt', 'trade-counter-authorization-v1-explicit-fingerprint-action-destination-once',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'SEND_NEGOTIATION_MESSAGE', 'MANAGER', 'manager-1',
                    'NEGOTIATION_MESSAGE_TEXT', 'hello',
                    '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824',
                    'PREPARED', '2026-09-05T00:00:00Z', NULL, NULL, NULL, '2026-09-05T00:00:00Z')
                """);
        }

        SQLException attemptDelete = assertThrows(SQLException.class,
            () -> execute(database, "DELETE FROM trade_counter_execution_attempts WHERE attempt_id='attempt-1'"));
        assertTrue(attemptDelete.getMessage().contains(
            "execution attempt audit record is retained and cannot be deleted"));

        SQLException grantDelete = assertThrows(SQLException.class,
            () -> execute(database, "DELETE FROM trade_counter_authorization_grants WHERE grant_id='grant-only'"));
        assertTrue(grantDelete.getMessage().contains(
            "authorization grant audit record is retained and cannot be deleted"));

        assertEquals(1, count(database,
            "SELECT COUNT(*) FROM trade_counter_execution_attempts WHERE attempt_id='attempt-1'"));
        assertEquals(1, count(database,
            "SELECT COUNT(*) FROM trade_counter_authorization_grants WHERE grant_id='grant-only'"));

        assertEquals(1, execute(database, """
            UPDATE trade_counter_execution_attempts
            SET state='IN_FLIGHT', in_flight_at='2026-09-05T00:01:00Z', updated_at='2026-09-05T00:01:00Z'
            WHERE attempt_id='attempt-1'
            """));
        assertEquals("IN_FLIGHT", text(database,
            "SELECT state FROM trade_counter_execution_attempts WHERE attempt_id='attempt-1'"));

        assertEquals(1, execute(database, """
            UPDATE trade_counter_authorization_grants
            SET consumed_at='2026-09-05T00:02:00Z'
            WHERE grant_id='grant-only'
            """));
        assertNotNull(text(database,
            "SELECT consumed_at FROM trade_counter_authorization_grants WHERE grant_id='grant-only'"));
    }

    private static int execute(Database database, String sql) throws Exception {
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static int count(Database database, String sql) throws Exception {
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String text(Database database, String sql) throws Exception {
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
