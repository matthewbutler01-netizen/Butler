package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerPresentationCloseoutBf1002Test {

    @Test
    void closeoutCoversFoundationalAndFinalManagerSurfaces() throws Exception {
        String script = source("scripts/butler-presentation-closeout-acceptance.ps1");

        List<String> expected = List.of(
            "BF-969", "BF-970", "BF-971", "BF-972", "BF-973",
            "BF-988", "BF-991", "BF-993", "BF-994", "BF-995",
            "BF-996", "BF-997", "BF-998", "BF-999", "BF-1000", "BF-1001"
        );

        int previous = -1;
        for (String id : expected) {
            int index = script.indexOf("Id = '" + id + "'");
            assertTrue(index > previous, id + " must be present in closeout order");
            previous = index;
        }

        assertTrue(script.contains("BUTLER MVP MANAGER PRESENTATION CLOSEOUT: PASS"));
        assertTrue(script.contains("presentation/navigation acceptance only"));
    }

    @Test
    void closeoutUsesExactAcceptanceScriptsForFinalSurfaces() throws Exception {
        String script = source("scripts/butler-presentation-closeout-acceptance.ps1");

        for (String acceptance : List.of(
            "butler-bf988-replacement-workflow-orientation-acceptance.ps1",
            "butler-bf991-lineup-manager-return-acceptance.ps1",
            "butler-bf993-dashboard-glance-scanability-acceptance.ps1",
            "butler-bf994-my-team-roster-scanability-acceptance.ps1",
            "butler-bf995-trade-analyzer-decision-flow-acceptance.ps1",
            "butler-bf996-league-manager-orientation-acceptance.ps1",
            "butler-bf997-franchise-scout-action-hierarchy-acceptance.ps1",
            "butler-bf998-decision-history-scope-acceptance.ps1",
            "butler-bf999-player-search-result-hierarchy-acceptance.ps1",
            "butler-bf1000-player-compare-completed-hierarchy-acceptance.ps1",
            "butler-bf1001-player-hub-action-hierarchy-acceptance.ps1"
        )) {
            assertTrue(script.contains(acceptance), "Missing closeout acceptance " + acceptance);
        }
    }

    @Test
    void closeoutRemainsAcceptanceOnly() throws Exception {
        String script = source("scripts/butler-presentation-closeout-acceptance.ps1");

        for (String forbidden : List.of(
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "https://api.sleeper.app",
            "Method = "POST"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer"
        )) {
            assertFalse(script.contains(forbidden), "Closeout added forbidden behavior " + forbidden);
        }
    }

    @Test
    void scriptRemainsAsciiOnly() throws Exception {
        String script = source("scripts/butler-presentation-closeout-acceptance.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(script));
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
        throw new IOException("BF-1002 test could not locate " + relativePath);
    }
}
