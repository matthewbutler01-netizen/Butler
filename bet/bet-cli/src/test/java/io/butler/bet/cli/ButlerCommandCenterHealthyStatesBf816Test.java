package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCommandCenterHealthyStatesBf816Test {

    @Test
    void bf815StagesHealthyStatePolishAfterWritingTheUnifiedPackage() throws Exception {
        String bf815 = source("scripts/butler-dashboard-bf815-unified-decision-package-transform.ps1");

        int write = bf815.indexOf("[System.IO.File]::WriteAllText($DashboardPath");
        int bf816 = bf815.indexOf("butler-dashboard-bf816-healthy-state-polish-transform.ps1");
        assertTrue(write >= 0);
        assertTrue(bf816 > write);
        assertTrue(bf815.contains("& $bf816Transform -DashboardPath $DashboardPath"));
    }

    @Test
    void lineupHealthyAndUnevaluatedStatesAreExplicit() throws Exception {
        String transform = source("scripts/butler-dashboard-bf816-healthy-state-polish-transform.ps1");

        assertTrue(transform.contains("Lineup review complete; no change proven"));
        assertTrue(transform.contains("This is a valid no-change result"));
        assertTrue(transform.contains("No lineup action is needed from this evidence frame"));
        assertTrue(transform.contains("Completed lineup review saved locally"));
        assertTrue(transform.contains("Completed no-change review saved locally"));

        assertTrue(transform.contains("Lineup review not requested yet"));
        assertTrue(transform.contains("NOT REVIEWED"));
        assertTrue(transform.contains("This is unevaluated, not a recommendation"));
        assertTrue(transform.contains("Projection coverage has not been evaluated because no AutoFill review was requested"));
        assertTrue(transform.contains("Lineup not evaluated yet"));
        assertTrue(transform.contains("No lineup review requested"));
    }

    @Test
    void waiverNoMoveAndTradeOnDemandRemainDeliberateStates() throws Exception {
        String transform = source("scripts/butler-dashboard-bf816-healthy-state-polish-transform.ps1");

        assertTrue(transform.contains("Waiver review complete; no move proven"));
        assertTrue(transform.contains("No waiver action is needed from this evidence frame"));
        assertTrue(transform.contains("This is a valid no-action outcome, not missing recommendation data"));
        assertTrue(transform.contains("No waiver action proven"));

        assertTrue(transform.contains("No trade evidence loaded"));
        assertTrue(transform.contains("Latest AutoFill hit an evidence gap"));
    }

    @Test
    void polishRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf816-healthy-state-polish-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("BUTLER_FANTASYPROS_API_KEY"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("$env:"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-816 test could not locate " + relativePath);
    }
}
