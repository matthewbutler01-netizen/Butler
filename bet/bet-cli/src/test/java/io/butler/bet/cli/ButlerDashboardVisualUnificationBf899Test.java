package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardVisualUnificationBf899Test {

    @Test
    void dashboardManagerChromeUsesTurfAndNeutralLanguage() throws Exception {
        String transform = source("scripts/butler-dashboard-bf899-visual-unification-transform.ps1");

        assertTrue(transform.contains("BF-899 dashboard visual unification"));
        assertTrue(transform.contains(".manager-hero{background:var(--surface)!important"));
        assertTrue(transform.contains(".manager-hero .command-meta span{border-color:var(--line)!important"));
        assertTrue(transform.contains(".manager-decision-card{border-color:var(--line)!important"));
        assertTrue(transform.contains(".manager-decision-card.primary{border-color:color-mix"));
        assertTrue(transform.contains(".manager-kind{color:var(--turf)!important"));
        assertTrue(transform.contains(".manager-chip{border-color:var(--line)!important"));
        assertTrue(transform.contains(".manager-chip.ok{border-color:color-mix"));
        assertTrue(transform.contains(".manager-chip.warn{border-color:color-mix"));
    }

    @Test
    void proofModeUsesSameNeutralSurfaceSystem() throws Exception {
        String transform = source("scripts/butler-dashboard-bf899-visual-unification-transform.ps1");

        assertTrue(transform.contains(".proof-mode{border-color:var(--line)!important"));
        assertTrue(transform.contains(".proof-card,.proof-evidence{border-color:var(--line)!important"));
        assertTrue(transform.contains(".manager-readonly{border-color:var(--line)!important"));
    }

    @Test
    void visualUnificationRunsAfterMobilePolishAndBeforeDiagnostics() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf898 = staging.indexOf("& $bf898Transform -DashboardPath $DashboardPath");
        int bf899 = staging.indexOf("& $bf899Transform -DashboardPath $DashboardPath");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing runs last");

        assertTrue(bf898 >= 0, "BF-898 staging marker missing");
        assertTrue(bf899 > bf898, "BF-899 must run after BF-898 mobile polish");
        assertTrue(bf857 > bf899, "BF-857 diagnostics must remain after BF-899 final visual pass");
        int bf899Gate = staging.lastIndexOf("if (Test-Path -LiteralPath $stagedCore -PathType Leaf)", bf899);
        assertTrue(bf899Gate >= 0 && bf899Gate < bf899,
            "BF-899 must remain gated to the full-app staged-core manager path");
    }

    @Test
    void visualUnificationRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf899-visual-unification-transform.ps1");

        int safetyScan = transform.indexOf("$installed -match");
        assertTrue(safetyScan > 0, "BF-899 safety scan must remain present");
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
                "BF-899 operational transform introduced provider/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
        assertTrue(transform.contains("dashboard visual unification introduced an operational/write marker"));
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
        throw new IOException("BF-899 test could not locate " + relativePath);
    }
}
