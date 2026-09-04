package io.butler.bet.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Installs shared terminal/authorization guards after governed manual terminal-outcome tables are
 * available. BF-396 remains the base executor-outcome guard; manual outcome types extend that guard
 * here instead of independently competing for the same SQLite trigger names.
 */
public final class TradeCounterManualTerminalGuardInstaller {
    public static final String INSTALLER_ID =
        "trade-counter-manual-terminal-guard-installer-v2-available-manual-outcomes";

    private TradeCounterManualTerminalGuardInstaller() {}

    public static void installSleeperTradeSupport(Database database) throws SQLException {
        install(database, true, false);
    }

    public static void installSleeperMessageSupport(Database database) throws SQLException {
        install(database, false, true);
    }

    private static void install(
        Database database,
        boolean requireSleeperTrade,
        boolean requireSleeperMessage) throws SQLException {
        Objects.requireNonNull(database, "database must not be null");
        try (var connection = database.openConnection();
             var statement = connection.createStatement()) {
            if (!tableExists(connection, "trade_counter_execution_outcomes")
                || !tableExists(connection, "trade_counter_execution_unknown_resolutions")) {
                throw new IllegalStateException(
                    "shared terminal guards require generic execution outcome tables");
            }

            boolean sleeperTrade = tableExists(connection, "sleeper_counter_trade_terminal_outcomes");
            boolean sleeperMessage = tableExists(connection, "sleeper_manual_message_terminal_outcomes");
            if (requireSleeperTrade && !sleeperTrade) {
                throw new IllegalStateException(
                    "Sleeper trade terminal guard support requires the Sleeper trade outcome table");
            }
            if (requireSleeperMessage && !sleeperMessage) {
                throw new IllegalStateException(
                    "Sleeper message terminal guard support requires the manual-message outcome table");
            }

            String terminalManual = manualTerminalClauses(sleeperTrade, sleeperMessage);
            String consumptionManual = manualConsumptionClauses(sleeperTrade, sleeperMessage);

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
                """ + terminalManual + """
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
                """ + consumptionManual + """
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'claimed authorization grant requires governed terminal outcome or UNKNOWN resolution before consumption');
                END
                """);
        }
    }

    private static String manualTerminalClauses(boolean sleeperTrade, boolean sleeperMessage) {
        StringBuilder sql = new StringBuilder();
        if (sleeperTrade) {
            sql.append("""
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
                """);
        }
        if (sleeperMessage) {
            sql.append("""
                      OR (
                          NEW.state = 'SUCCEEDED'
                          AND EXISTS (
                              SELECT 1
                              FROM sleeper_manual_message_terminal_outcomes m
                              WHERE m.attempt_id = OLD.attempt_id
                                AND m.grant_id = OLD.grant_id
                                AND m.payload_sha256 = OLD.payload_sha256
                                AND m.terminal_state = 'SUCCEEDED'
                          )
                      )
                """);
        }
        return sql.toString();
    }

    private static String manualConsumptionClauses(boolean sleeperTrade, boolean sleeperMessage) {
        StringBuilder sql = new StringBuilder();
        if (sleeperTrade) {
            sql.append("""
                      OR EXISTS (
                          SELECT 1
                          FROM sleeper_counter_trade_terminal_outcomes s
                          WHERE s.grant_id = OLD.grant_id
                            AND s.terminal_state = 'SUCCEEDED'
                            AND s.grant_disposition = 'CONSUME'
                      )
                """);
        }
        if (sleeperMessage) {
            sql.append("""
                      OR EXISTS (
                          SELECT 1
                          FROM sleeper_manual_message_terminal_outcomes m
                          WHERE m.grant_id = OLD.grant_id
                            AND m.terminal_state = 'SUCCEEDED'
                            AND m.grant_disposition = 'CONSUME'
                      )
                """);
        }
        return sql.toString();
    }

    private static boolean tableExists(Connection connection, String table)
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
