package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardPriorityDedupBf900Test {

    @Test
    void queueSkipsPriorityOneBecauseHeroAlreadyOwnsIt() throws Exception {
        String transform = source("scripts/butler-dashboard-bf900-priority-dedup-transform.ps1");

        assertTrue(transform.contains("BF-900: priority 01 already owns the hero"));
        assertTrue(transform.contains("Select-Object -Skip 1"));
        assertTrue(transform.contains("No additional priorities are waiting behind priority 01."));
        assertTrue(transform.contains("Priority 01 is summarized above."));
    }

    @Test
    void polishRunsAfterVisualUnificationAndBeforeDiagnostics() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf899 = staging.indexOf("& $bf899Transform -DashboardPath $DashboardPath");
        int bf900 = staging.indexOf("& $bf900Transform -DashboardPath $DashboardPath");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing runs last");

        assertTrue(bf899 >= 0, "BF-899 staging marker missing");
        assertTrue(bf900 > bf899, "BF-900 must run after BF-899 visual unification");
        assertTrue(bf857 > bf900, "BF-857 diagnostics must remain after BF-900 hierarchy polish");
    }

    @Test
    void polishRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf900-priority-dedup-transform.ps1");

        int safetyScan = transform.indexOf("$installed -match");
        assertTrue(safetyScan > 0, "BF-900 safety scan must remain present");
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-900 operational transform introduced provider/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
        assertTrue(transform.contains("dashboard queue polish introduced an operational/write marker"));
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
        throw new IOException("BF-900 test could not locate " + relativePath);
    }
}
