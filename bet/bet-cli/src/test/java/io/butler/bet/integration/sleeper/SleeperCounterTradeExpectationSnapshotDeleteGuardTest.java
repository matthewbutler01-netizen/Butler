package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCounterTradeExpectationSnapshotDeleteGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void immutableTradeExpectationSnapshotRejectsDirectDelete() throws Exception {
        Database database = new Database(tempDir.resolve("bf441-snapshot-delete.db"));
        database.initialize();
        new SleeperCounterTradeExpectationSnapshotRepository(database).initialize();

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("DROP TRIGGER trg_sleeper_counter_trade_snapshot_trusted_handoff");
            assertEquals(1, statement.executeUpdate("""
                INSERT INTO sleeper_counter_trade_expectation_snapshots(
                    claim_id, handoff_id, snapshot_policy_id, resolver_policy_id,
                    butler_league_id, side_a_team_id, side_b_team_id, sleeper_league_id,
                    movement_json, movement_sha256, snapshotted_at)
                VALUES (
                    'claim-delete-test', 'handoff-delete-test',
                    'sleeper-counter-trade-expectation-snapshot-v1-handoff-provider-movement',
                    'sleeper-trade-expectation-resolution-v1-external-id-owned-assets',
                    'league-delete-test', 'team-a', 'team-b', '289646328504385536',
                    '{"rosterIds":[1,2],"playerAdds":{},"playerDrops":{},"draftPicks":[]}',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    '2026-09-05T02:00:00Z')
                """));

            var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM sleeper_counter_trade_expectation_snapshots"
                    + " WHERE claim_id='claim-delete-test'"));
            assertTrue(error.getMessage().contains("Sleeper trade expectation snapshot is immutable"));

            try (var rs = statement.executeQuery(
                "SELECT claim_id FROM sleeper_counter_trade_expectation_snapshots"
                    + " WHERE claim_id='claim-delete-test'")) {
                assertTrue(rs.next());
                assertEquals("claim-delete-test", rs.getString(1));
            }
        }
    }
}
