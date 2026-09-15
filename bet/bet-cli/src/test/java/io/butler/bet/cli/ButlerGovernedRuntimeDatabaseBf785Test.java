package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerGovernedRuntimeDatabaseBf785Test {

    @Test
    void appRequiresGovernedDatabaseBeforePersistingLeagueOrLaunchingShell() throws Exception {
        String script = source("scripts/butler-app.ps1");

        assertTrue(script.contains("BF-785 BLOCKED: governed Butler runtime database is missing at $databasePath."));
        assertTrue(script.contains("The Butler runtime package is code/runtime-only; restore or migrate an existing governed Butler database before launching."));

        int leagueResolution = script.indexOf("$selectedLeagueId = if ($null -ne $configuredLeagueId)");
        int dataResolution = script.indexOf("$dataDir = Resolve-ButlerAppDataDir");
        int databaseCheck = script.indexOf("if (-not (Test-Path -LiteralPath $databasePath -PathType Leaf))");
        int configWrite = script.indexOf("[IO.File]::WriteAllText($configPath");
        int shellLaunch = script.indexOf("& $appShell -LeagueId $selectedLeagueId");

        assertTrue(leagueResolution >= 0);
        assertTrue(dataResolution > leagueResolution);
        assertTrue(databaseCheck > dataResolution);
        assertTrue(configWrite > databaseCheck);
        assertTrue(shellLaunch > configWrite);
    }

    @Test
    void missingDatabaseNoLongerDependsOnExistingLeagueConfiguration() throws Exception {
        String script = source("scripts/butler-app.ps1");

        assertTrue(script.contains("BF-770 BLOCKED: legacy Butler database remains in the source tree. Run scripts\\butler-migrate-runtime-data.ps1 before launching Butler."));
        assertFalse(script.contains("if ($null -ne $configuredLeagueId) {\n        throw \"BF-770 BLOCKED: configured Butler runtime database is missing"));
        assertFalse(script.contains("configured Butler runtime database is missing at $databasePath"));
    }

    @Test
    void resetLeagueStillExitsBeforeRuntimeDatabasePreflight() throws Exception {
        String script = source("scripts/butler-app.ps1");

        int resetBlock = script.indexOf("if ($ResetLeague) {");
        int resetExit = script.indexOf("exit 0", resetBlock);
        int dataResolution = script.indexOf("$dataDir = Resolve-ButlerAppDataDir");

        assertTrue(resetBlock >= 0);
        assertTrue(resetExit > resetBlock);
        assertTrue(dataResolution > resetExit);
    }

    @Test
    void launcherSourceRemainsAsciiOnly() throws Exception {
        String script = source("scripts/butler-app.ps1");
        assertTrue(script.chars().allMatch(ch -> ch <= 0x7f));
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
        throw new IOException("BF-785 test could not locate " + relativePath);
    }
}
