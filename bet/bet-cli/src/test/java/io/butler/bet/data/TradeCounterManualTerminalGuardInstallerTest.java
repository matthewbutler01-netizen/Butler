package io.butler.bet.data;

import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterManualTerminalGuardInstallerTest {
    @TempDir
    Path tempDir;

    @Test
    void sleeperTradeThenGenericReinitializePreservesCombinedGuards() throws Exception {
        Database database = database("trade-then-generic");
        new SleeperCounterTradeOutcomeCoordinator(database).initialize();

        String terminalBefore = triggerSql(database,
            "trg_trade_counter_execution_terminal_outcome_required");
        String consumptionBefore = triggerSql(database,
            "trg_trade_counter_execution_claimed_grant_consumption_guard");
        assertCombined(terminalBefore, consumptionBefore);

        new TradeCounterExecutionOutcomeCoordinator(database).initialize();

        String terminalAfter = triggerSql(database,
            "trg_trade_counter_execution_terminal_outcome_required");
        String consumptionAfter = triggerSql(database,
            "trg_trade_counter_execution_claimed_grant_consumption_guard");
        assertCombined(terminalAfter, consumptionAfter);
    }

    @Test
    void genericThenSleeperTradeUpgradesBaseGuards() throws Exception {
        Database database = database("generic-then-trade");
        new TradeCounterExecutionOutcomeCoordinator(database).initialize();

        String baseTerminal = triggerSql(database,
            "trg_trade_counter_execution_terminal_outcome_required");
        String baseConsumption = triggerSql(database,
            "trg_trade_counter_execution_claimed_grant_consumption_guard");
        assertTrue(baseTerminal.contains("trade_counter_execution_outcomes"));
        assertTrue(baseConsumption.contains("trade_counter_execution_outcomes"));
        assertFalse(baseTerminal.contains("sleeper_counter_trade_terminal_outcomes"));
        assertFalse(baseConsumption.contains("sleeper_counter_trade_terminal_outcomes"));

        new SleeperCounterTradeOutcomeCoordinator(database).initialize();

        assertCombined(
            triggerSql(database, "trg_trade_counter_execution_terminal_outcome_required"),
            triggerSql(database, "trg_trade_counter_execution_claimed_grant_consumption_guard"));
    }

    @Test
    void sharedInstallerIsIdempotentOnceRequiredTablesExist() throws Exception {
        Database database = database("idempotent");
        new SleeperCounterTradeOutcomeCoordinator(database).initialize();
        String before = triggerSql(database,
            "trg_trade_counter_execution_terminal_outcome_required");

        TradeCounterManualTerminalGuardInstaller.installSleeperTradeSupport(database);
        TradeCounterManualTerminalGuardInstaller.installSleeperTradeSupport(database);

        String after = triggerSql(database,
            "trg_trade_counter_execution_terminal_outcome_required");
        assertTrue(before.equals(after));
        assertCombined(
            after,
            triggerSql(database, "trg_trade_counter_execution_claimed_grant_consumption_guard"));
    }

    @Test
    void sharedInstallerFailsClosedBeforeManualOutcomeTableExists() throws Exception {
        Database database = database("missing-manual-table");
        new TradeCounterExecutionOutcomeCoordinator(database).initialize();

        assertThrows(IllegalStateException.class,
            () -> TradeCounterManualTerminalGuardInstaller.installSleeperTradeSupport(database));
    }

    private Database database(String suffix) throws Exception {
        Database database = new Database(tempDir.resolve("bf415-" + suffix + ".db"));
        database.initialize();
        return database;
    }

    private static void assertCombined(String terminalSql, String consumptionSql) {
        assertTrue(terminalSql.contains("trade_counter_execution_outcomes"));
        assertTrue(terminalSql.contains("sleeper_counter_trade_terminal_outcomes"));
        assertTrue(consumptionSql.contains("trade_counter_execution_outcomes"));
        assertTrue(consumptionSql.contains("trade_counter_execution_unknown_resolutions"));
        assertTrue(consumptionSql.contains("sleeper_counter_trade_terminal_outcomes"));
    }

    private static String triggerSql(Database database, String trigger) throws Exception {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?")) {
            statement.setString(1, trigger);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) throw new AssertionError("missing trigger " + trigger);
                return rs.getString(1);
            }
        }
    }
}
