package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPositionOutlookBf828Test {

    @Test
    void bf828RunsBeforeSharedVisualTransform() throws Exception {
        String bf827 = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        int bf828 = bf827.indexOf("& $bf828Transform -CorePath $CorePath");
        int bf829 = bf827.indexOf("& $bf829Transform -CorePath $CorePath");

        assertTrue(bf828 >= 0, "BF-828 must be staged from the My Team transform chain");
        assertTrue(bf829 > bf828, "BF-828 semantic presentation must run before the shared visual transform");
        assertTrue(bf827.contains("butler-app-bf828-position-outlook-partial-transform.ps1"));
    }

    @Test
    void bf828TargetsTheLivePostBf803PositionRenderer() throws Exception {
        String bf803 = source("scripts/butler-app-bf803-manager-ui-transform.ps1");
        String transform = source("scripts/butler-app-bf828-position-outlook-partial-transform.ps1");

        for (String stagedMarker : new String[]{
                "$positionHtml = ''",
                "card position-card",
                "$(ConvertTo-HtmlText $position.DirectStarters) starter slot(s)",
                "Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue) &middot; total value $(ConvertTo-HtmlText $position.TotalPositionValue)",
                "<div class=`\"pressure-tier`\">Unavailable</div>"
        }) {
            assertTrue(bf803.contains(stagedMarker), "BF-803 staged renderer is missing expected marker " + stagedMarker);
            assertTrue(transform.contains(stagedMarker), "BF-828 must match the live BF-803 staged renderer marker " + stagedMarker);
        }

        assertFalse(transform.contains("$pressureHtml = \"\""),
                "BF-828 must not target the pre-BF-803 pressureHtml renderer");
        assertTrue(transform.contains("staged BF-803 Position Outlook rendering contract"));
    }

    @Test
    void partialPositionsExposeExactGovernedCoverageWithoutInventingTier() throws Exception {
        String transform = source("scripts/butler-app-bf828-position-outlook-partial-transform.ps1");

        for (String marker : new String[]{
                "Coverage needed",
                "card position-card position-partial",
                "Value coverage $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players)",
                "stale $(ConvertTo-HtmlText $position.Stale)",
                "missing $(ConvertTo-HtmlText $position.Missing)",
                "$(ConvertTo-HtmlText $position.DirectStarters) direct starter(s)",
                "$(ConvertTo-HtmlText $reasonText)",
                "No position tier is inferred until the governed positional-pressure evidence is complete."
        }) {
            assertTrue(transform.contains(marker), "missing BF-828 partial-evidence marker " + marker);
        }
    }

    @Test
    void completePositionPathKeepsExistingGovernedTierAndValueContext() throws Exception {
        String transform = source("scripts/butler-app-bf828-position-outlook-partial-transform.ps1");

        assertTrue(transform.contains("if ($position.Available)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $position.Tier)"));
        assertTrue(transform.contains("Starter coverage $(ConvertTo-HtmlText $position.StarterCoverageValue)"));
        assertTrue(transform.contains("total value $(ConvertTo-HtmlText $position.TotalPositionValue)"));
        assertTrue(transform.contains("Value coverage $(ConvertTo-HtmlText $position.Valued)/$(ConvertTo-HtmlText $position.Players)"));
    }

    @Test
    void governedProducerAlreadyEmitsTeamCountsWhenPositionIsUnavailable() throws Exception {
        String producer = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerLeaguePositionalPressureCli.java");

        int unavailableReason = producer.indexOf("if (!pressure.available())");
        int teamRows = producer.indexOf("for (var team : pressure.teams())");

        assertTrue(unavailableReason >= 0);
        assertTrue(teamRows > unavailableReason,
                "team evidence rows must remain emitted after the unavailable-position reason");
        assertTrue(producer.contains("players=%d valued=%d stale=%d missing=%d team-id=%s"));
    }

    @Test
    void bf828RemainsPresentationOnlyAndReadOnly() throws Exception {
        String transform = source("scripts/butler-app-bf828-position-outlook-partial-transform.ps1");

        assertTrue(transform.contains("foreach ($forbidden"), "BF-828 must keep its fail-closed safety scan");
        assertFalse(transform.contains("https://api.sleeper.app/v1/"));
        assertFalse(transform.contains("SleeperClient"));
        assertFalse(transform.contains("$request.Method = \"POST\""));
        assertFalse(transform.contains("Invoke-RestMethod -"));
        assertFalse(transform.contains("Invoke-WebRequest -"));
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
        throw new IOException("BF-828 test could not locate " + relativePath);
    }
}
