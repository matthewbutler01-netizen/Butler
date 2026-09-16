package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCommandCenterPolishBf805Test {

    @Test
    void polishUnifiesManagerNavigationWithoutChangingRoutes() throws Exception {
        String transform = source("scripts/butler-dashboard-bf805-command-center-polish-transform.ps1");

        assertTrue(transform.contains("href=\"/\">Dashboard</a>"));
        assertTrue(transform.contains("href=\"/team\">My Team</a>"));
        assertTrue(transform.contains("href=\"/waivers\">Waiver Board</a>"));
        assertTrue(transform.contains("href=\"/league\">League</a>"));
        assertTrue(transform.contains("href=\"/trade\">Trade Lab</a>"));
        assertTrue(transform.contains("href=\"/history\">History</a>"));

        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("--team-bundle-autofill"));
        assertFalse(transform.contains("FantasyProsWeeklyProjectionProvider"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
    }

    @Test
    void polishUsesManagerCopyInsteadOfPersistedImplementationText() throws Exception {
        String transform = source("scripts/butler-dashboard-bf805-command-center-polish-transform.ps1");

        assertTrue(transform.contains("Butler could not identify one clear add/drop move supported strongly enough by the current evidence"));
        assertTrue(transform.contains("Butler found one add/drop move that passed the current governed checks"));
        assertTrue(transform.contains("supporting evidence is old enough that refreshing it first is recommended"));
        assertTrue(transform.contains("$text = $text.Replace($oldWhy, $newWhy)"));
        assertTrue(transform.contains("$whyCopy = switch ($state)"));
        assertFalse(transform.contains("final method"));
        assertFalse(transform.contains("cross-position ties"));
    }

    @Test
    void polishStagesAfterCommandCenter() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf804 = staging.indexOf("& $bf804Transform -DashboardPath $DashboardPath");
        int bf805 = staging.indexOf("& $bf805Transform -DashboardPath $DashboardPath");
        assertTrue(bf804 >= 0, "BF-804 Command Center staging must remain present");
        assertTrue(bf805 > bf804, "BF-805 polish must run after BF-804 installs the Command Center");
        assertTrue(staging.contains("butler-dashboard-bf805-command-center-polish-transform.ps1"));
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
        throw new IOException("BF-805 test could not locate " + relativePath);
    }
}
