package io.butler.bet.data;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Installs the shared terminal/authorization guards after governed manual terminal-outcome tables
 * are available. BF-396 remains the base executor-outcome guard; manual outcome types extend that
 * guard here instead of independently competing for the same SQLite trigger names.
 */
public final class TradeCounterManualTerminalGuardInstaller {
    public static final String INSTALLER_ID =
        "trade-counter-manual-terminal-guard-installer-v1-generic-plus-sleeper-trade";

    private TradeCounterManualTerminalGuardInstaller() {}

    public static void installSleeperTradeSupport(Database database) throws SQLException {
        Objects.requireNonNull(database, "database must not be null");
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            if (!tableExists(connection, "trade_counter_execution_outcomes")
                || !tableExists(connection, "trade_counter_execution_unknown_resolutions")
                || !tableExists(connection, "sleeper_counter_trade_terminal_outcomes")) {
                throw new IllegalStateException(
                    "shared terminal guards require generic and Sleeper trade outcome tables");
            }

            statement.executeUpdate(
                "DROP TRIGGER IF EXISTS trg_trade_counter_execution_terminal_outcome_required");
            statement.executeUpdate("""
                CREATE TRIGGER trg_trade_counter_execution_terminal_outcome_required
                BEFORE UPDATE OF state ON trade_counter_execution_attempts
                FOR EACH ROW
                WHEN OLD.state = 'IN_FLIGHT'
                  AND NEW.state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                  AND NOT (
                      EXISTS (
                          SELECT 1
                          FROM trade_counter_execution_outcomes o
                          WHERE o.attempt_id = OLD.attempt_id
                            AND o.grant_id = OLD.grant_id
                            AND o.payload_sha256 = OLD.payload_sha256
                            AND o.attempt_terminal_state = NEW.state
                      )
                      OR (
                          NEW.state = 'SUCCEEDED'
                          AND EXISTS (
                              SELECT 1
                              FROM sleeper_counter_trade_terminal_outcomes s
                              WHERE s.attempt_id = OLD.attempt_id
                                AND s.grant_id = OLD.grant_id
                                AND s.terminal_state = 'SUCCEEDED'
                          )
                      )
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'IN_FLIGHT execution attempt requires durable governed outcome before terminal transition');
                END
                """);

            statement.executeUpdate(
                "DROP TRIGGER IF EXISTS trg_trade_counter_execution_claimed_grant_consumption_guard");
            statement.executeUpdate("""
                CREATE TRIGGER trg_trade_counter_execution_claimed_grant_consumption_guard
                BEFORE UPDATE OF consumed_at ON trade_counter_authorization_grants
                FOR EACH ROW
                WHEN OLD.consumed_at IS NULL
                  AND NEW.consumed_at IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM trade_counter_execution_claims c WHERE c.grant_id = OLD.grant_id
                  )
                  AND NOT (
                      EXISTS (
                          SELECT 1
                          FROM trade_counter_execution_outcomes o
                          WHERE o.grant_id = OLD.grant_id
                            AND o.grant_disposition = 'CONSUME'
                            AND o.outcome_state IN ('CONFIRMED_SUCCESS', 'CONFIRMED_NO_ACTION_FAILURE')
                      )
                      OR EXISTS (
                          SELECT 1
                          FROM trade_counter_execution_unknown_resolutions r
                          JOIN trade_counter_execution_outcomes o ON o.outcome_id = r.outcome_id
                          WHERE r.grant_id = OLD.grant_id
                            AND r.grant_disposition = 'CONSUME'
                            AND o.outcome_state = 'UNKNOWN_PENDING_RECONCILIATION'
                      )
                      OR EXISTS (
                          SELECT 1
                          FROM sleeper_counter_trade_terminal_outcomes s
                          WHERE s.grant_id = OLD.grant_id
                            AND s.terminal_state = 'SUCCEEDED'
                            AND s.grant_disposition = 'CONSUME'
                      )
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'claimed authorization grant requires governed terminal outcome or UNKNOWN resolution before consumption');
                END
                """);
        }
    }

    private static boolean tableExists(java.sql.Connection connection, String table)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
