package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRosterIntelligenceBf827Test {

    @Test
    void bf817StagesBf827AfterLineupAvailabilityAndBeforeWaiverAdvisor() throws Exception {
        String bf817 = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        int bf825 = bf817.indexOf("& $bf825Transform -CorePath $CorePath");
        int bf827 = bf817.indexOf("& $bf827Transform -CorePath $CorePath");
        int bf818 = bf817.indexOf("& $bf818Transform -DashboardPath $stagedDashboard");

        assertTrue(bf825 >= 0);
        assertTrue(bf827 > bf825);
        assertTrue(bf818 > bf827);
        assertTrue(bf817.contains("butler-app-bf827-roster-intelligence-transform.ps1"));
    }

    @Test
    void transformReplacesGenericUnavailableCardsWithEvidenceBackedLocalStates() throws Exception {
        String transform = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        assertTrue(transform.contains("Rank pending"));
        assertTrue(transform.contains("Coverage needed"));
        assertTrue(transform.contains("Direction pending"));
        assertTrue(transform.contains("Compared with $($Roster.LeagueName) franchises by Butler governed total franchise value."));
        assertTrue(transform.contains("Starter value $($Strength.StarterValue); roster player value $($Strength.TotalPlayerValue)"));
        assertTrue(transform.contains("Position tiers: $positionTierText"));
        assertTrue(transform.contains("Team direction is the governed posture from those two dimensions."));
        assertTrue(transform.contains("Roster intelligence evidence"));
    }

    @Test
    void transformFailsClosedCardByCardAndRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        assertTrue(transform.contains("League comparison needs complete governed franchise-value coverage"));
        assertTrue(transform.contains("Complete current value coverage is required."));
        assertTrue(transform.contains("Complete governed competitive and roster posture evidence is required"));
        assertTrue(transform.contains("roster-intelligence presentation introduced a write or direct provider path"));

        // The transform itself must not introduce credentials, endpoints, or executable network helpers.
        // Provider/write names may appear only as inert strings inside the transform's fail-closed safety guard,
        // so test actual executable/network markers instead of coupling to the guard's exact regex spelling.
        assertFalse(transform.contains("$env:"));
        assertFalse(transform.contains("https://api.sleeper"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("Start-Process"));
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
        throw new IOException("BF-827 test could not locate " + relativePath);
    }
}
