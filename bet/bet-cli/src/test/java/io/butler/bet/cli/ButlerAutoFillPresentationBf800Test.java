package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAutoFillPresentationBf800Test {

    @Test
    void normalTeamBundleRemainsProviderFreeAndAutoFillRequiresExplicitFlag() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");
        String renderer = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerAutoFillLineupRecommendationCli.java");

        assertTrue(bundle.contains("static final String AUTOFILL = \"AUTOFILL\";"));
        assertTrue(bundle.contains("boolean includeAutoFill = validAutoFillArgs(args);"));
        assertTrue(bundle.contains("includeAutoFill\n                    ? autoFillSafely(database, rosterContextReport)\n                    : null"));
        assertTrue(bundle.contains("if (includeAutoFill) emit(AUTOFILL, autoFill);"));
        assertTrue(bundle.contains("normal My Team remains read-only and provider-free for BF-800 AutoFill"));
        assertTrue(renderer.contains("AutoFill is preview-only"));
        assertTrue(renderer.contains("No Butler or Sleeper lineup write was executed"));
        assertFalse(bundle.contains("submit lineup"));
        assertFalse(renderer.contains("HttpRequest"));

        assertTrue(ButlerMyTeamEvidenceBundleCli.validAutoFillArgs(new String[]{"league", "--autofill"}));
        assertFalse(ButlerMyTeamEvidenceBundleCli.validAutoFillArgs(new String[]{"league"}));
        assertFalse(ButlerMyTeamEvidenceBundleCli.validAutoFillArgs(new String[]{"league", "--anything-else"}));
    }

    @Test
    void outerDispatchSeparatesNormalAndAutoFillTeamBundles() {
        assertTrue(ButlerSleeperLiveWaiverTargetRosterContextAuditCli.isTeamBundle(
            new String[]{"league", "--team-bundle"}));
        assertFalse(ButlerSleeperLiveWaiverTargetRosterContextAuditCli.isTeamAutoFillBundle(
            new String[]{"league", "--team-bundle"}));

        assertTrue(ButlerSleeperLiveWaiverTargetRosterContextAuditCli.isTeamAutoFillBundle(
            new String[]{"league", "--team-bundle-autofill"}));
        assertFalse(ButlerSleeperLiveWaiverTargetRosterContextAuditCli.isTeamBundle(
            new String[]{"league", "--team-bundle-autofill"}));
    }

    @Test
    void stagedMyTeamPresentationMakesFantasyProsExplicitGetOnlyAction() throws Exception {
        String transform = source("scripts/butler-app-bf800-autofill-transform.ps1");
        String core = source("scripts/butler-app-shell-core-single.ps1");
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        assertTrue(transform.contains("function New-AutoFillIdleView"));
        assertTrue(transform.contains("function ConvertTo-AutoFillView"));
        assertTrue(transform.contains("function ConvertTo-AutoFillHtml"));
        assertTrue(transform.contains("href=\"/team/autofill\""));
        assertTrue(transform.contains("if ($path -eq \"/team/autofill\")"));
        assertTrue(transform.contains("$LeagueId --team-bundle-autofill"));
        assertTrue(transform.contains("Get-TeamEvidenceBundleSection -Text $bundleText -Name \"AUTOFILL\""));
        assertTrue(transform.contains("$autoFill = New-AutoFillIdleView"));
        assertTrue(transform.contains("FantasyPros is contacted only when you choose AutoFill"));
        assertTrue(transform.contains("Projection data:"));
        assertTrue(transform.contains("Butler did not submit a lineup to Sleeper"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains(".POST"));

        assertTrue(core.contains("function ConvertTo-TeamHtml {"));
        assertTrue(core.contains("-Arguments \"$LeagueId --team-bundle\" -BoundaryName \"BF-692\""));
        assertFalse(core.contains("--team-bundle-autofill"));
        assertTrue(core.contains("It does not create a new score, recommend a lineup"));

        int bf742 = staging.indexOf("& $bf742Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        int bf800 = staging.indexOf("& $bf800Transform -CorePath $stagedCore");
        assertTrue(bf742 >= 0, "BF-742 staging invocation must remain present");
        assertTrue(bf800 > bf742, "BF-800 must transform the staged core after BF-742");
        assertTrue(staging.contains("if (Test-Path -LiteralPath $stagedCore -PathType Leaf)"));
    }

    @Test
    void fantasyProsCredentialStaysInExternalEnvironmentBoundary() throws Exception {
        String provider = source("bet/bet-cli/src/main/java/io/butler/bet/integration/FantasyProsWeeklyProjectionProvider.java");

        assertTrue(provider.contains("BUTLER_FANTASYPROS_API_KEY"));
        assertTrue(provider.contains("https://api.fantasypros.com/public/v2/json/"));
        assertTrue(provider.contains(".header(\"x-api-key\", apiKey)"));
        assertFalse(provider.contains("api_key="));
        assertFalse(provider.contains("x-api-key: "));
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
        throw new IOException("BF-800 test could not locate " + relativePath);
    }
}
