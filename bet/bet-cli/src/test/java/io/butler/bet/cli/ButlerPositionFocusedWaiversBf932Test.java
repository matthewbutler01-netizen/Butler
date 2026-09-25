package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPositionFocusedWaiversBf932Test {

    @Test
    void myTeamPreservesExactPositionIntoWaiverBoard() throws Exception {
        String transform = source("scripts/butler-app-bf932-position-focused-waivers-transform.ps1");

        assertTrue(transform.contains("/players?q=$positionHref"));
        assertTrue(transform.contains("Browse $(ConvertTo-HtmlText $position.Position) players"));
        assertTrue(transform.contains("/waivers?position=$positionHref"));
        assertTrue(transform.contains("Check $(ConvertTo-HtmlText $position.Position) waivers"));
        assertTrue(transform.contains("$dashboardRequestTarget = if ($path -eq \"/waivers\") { $parts[1] } else { $path }"));
        assertFalse(transform.contains("{ $requestTarget }"));
        assertTrue(transform.contains("Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $dashboardRequestTarget"));
    }

    @Test
    void waiverBoardFiltersOnlyAuthorizedCandidatesForDisplay() throws Exception {
        String transform = source("scripts/butler-dashboard-bf932-position-focused-waivers-transform.ps1");

        assertTrue(transform.contains("$PositionFocus"));
        assertTrue(transform.contains("$normalizedPositionFocus"));
        assertTrue(transform.contains("$displayCandidates"));
        assertTrue(transform.contains("Where-Object"));
        assertTrue(transform.contains("[string]$_.Position -ceq $normalizedPositionFocus"));
        assertTrue(transform.contains("foreach ($candidate in $displayCandidates)"));
        assertTrue(transform.contains("No authorized $(ConvertTo-HtmlText $normalizedPositionFocus) candidates"));
        assertTrue(transform.contains("Source order remains unchanged."));
        assertTrue(transform.contains("/waivers?position=QB"));
        assertTrue(transform.contains("/waivers?position=RB"));
        assertTrue(transform.contains("/waivers?position=WR"));
        assertTrue(transform.contains("/waivers?position=TE"));
    }

    @Test
    void dashboardRouteParsesOnlySupportedPositionFocus() throws Exception {
        String transform = source("scripts/butler-dashboard-bf932-position-focused-waivers-transform.ps1");

        assertTrue(transform.contains("function Get-WaiverPositionFocusFromRequestTarget"));
        assertTrue(transform.contains("QB"));
        assertTrue(transform.contains("RB"));
        assertTrue(transform.contains("WR"));
        assertTrue(transform.contains("TE"));
        assertTrue(transform.contains("-PositionFocus (Get-WaiverPositionFocusFromRequestTarget -RequestTarget $parts[1])"));
    }

    @Test
    void stagingKeepsBf932AfterContextSourcesAndBeforeFinalPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf930 = staging.indexOf("& $bf930CoreTransform -CorePath $stagedCore");
        int bf932Core = staging.indexOf("& $bf932CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");
        assertTrue(bf930 >= 0 && bf932Core > bf930 && bf884 > bf932Core);

        int bf931 = staging.indexOf("& $bf931DashboardTransform -DashboardPath $DashboardPath");
        int bf932Dashboard = staging.indexOf("& $bf932DashboardTransform -DashboardPath $DashboardPath");
        int bf898 = staging.indexOf("& $bf898Transform");
        assertTrue(bf931 >= 0 && bf932Dashboard > bf931 && bf898 > bf932Dashboard);
    }

    @Test
    void managerJourneyExercisesExactPositionFocusedWaiverRoute() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("/waivers?position=QB"));
        assertTrue(journey.contains("Check QB waivers"));
        assertTrue(journey.contains("/waivers?position=RB"));
        assertTrue(journey.contains("Check RB waivers"));
        assertTrue(journey.contains("/waivers?position=WR"));
        assertTrue(journey.contains("Check WR waivers"));
        assertTrue(journey.contains("/waivers?position=TE"));
        assertTrue(journey.contains("Check TE waivers"));
        assertTrue(journey.contains("My Team position Waiver Board"));
        assertTrue(journey.contains("Position focus: QB"));
        assertTrue(journey.contains("Source order remains unchanged."));
        assertTrue(journey.contains("Write-Pass -Label 'My Team position Waiver Board'"));
    }

    @Test
    void featureFilesRemainAsciiAndDoNotIntroduceWrites() throws Exception {
        String core = source("scripts/butler-app-bf932-position-focused-waivers-transform.ps1");
        String dashboard = source("scripts/butler-dashboard-bf932-position-focused-waivers-transform.ps1");

        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(core));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(dashboard));

        String coreInstalled = core.substring(0, core.indexOf("foreach ($required in @("));
        assertFalse(coreInstalled.contains("submitTransaction"));
        assertFalse(coreInstalled.contains("setFaab"));

        String dashboardInstalled = dashboard.substring(0, dashboard.indexOf("foreach ($required in @("));
        assertFalse(dashboardInstalled.contains("submitTransaction"));
        assertFalse(dashboardInstalled.contains("setFaab"));
        assertFalse(dashboardInstalled.contains("Invoke-RestMethod"));
        assertFalse(dashboardInstalled.contains("Invoke-WebRequest"));
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
        throw new IOException("BF-932 test could not locate " + relativePath);
    }
}
