package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeAssetPickerGroupsBf933Test {

    @Test
    void tradePickerGroupsPlayersAndDraftPicks() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("$playerAssets = @($Team.Assets | Where-Object { $_.Type -ceq 'player' } | Sort-Object Label)"));
        assertTrue(trade.contains("$pickAssets = @($Team.Assets | Where-Object { $_.Type -cne 'player' } | Sort-Object Label)"));
        assertTrue(trade.contains("Class = 'players'; Label = 'Players'"));
        assertTrue(trade.contains("Class = 'picks'; Label = 'Draft picks'"));
        assertTrue(trade.contains("No player assets are available."));
        assertTrue(trade.contains("No draft picks are available."));
        assertTrue(trade.contains("$selectedSet.Contains([string]$asset.Token)"));
        assertTrue(trade.contains("$(ConvertTo-HtmlText $asset.Token)"));
    }

    @Test
    void tradePickerAddsCompactGroupPresentation() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains(".asset-groups{display:grid"));
        assertTrue(trade.contains(".asset-group{padding:12px"));
        assertTrue(trade.contains(".asset-group-head"));
        assertTrue(trade.contains(".asset-count"));
        assertTrue(trade.contains("asset-group $($group.Class)"));
        assertTrue(trade.contains("$(@($group.Assets).Count)"));
    }

    @Test
    void managerJourneyRequiresGroupedPickerOnLoadedTradeAnalyzer() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Stage 'League exact Trade Analyzer'"));
        assertTrue(journey.contains("asset-group players"));
        assertTrue(journey.contains("asset-group picks"));
        assertTrue(journey.contains("Draft picks"));
    }

    @Test
    void bf933RemainsReadOnlyPresentationOnly() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        int pickerStart = trade.indexOf("function ConvertTo-TradeAssetCheckboxes");
        int pickerEnd = trade.indexOf("function ConvertTo-TradeLabHtml", pickerStart);
        assertTrue(pickerStart >= 0 && pickerEnd > pickerStart);
        String picker = trade.substring(pickerStart, pickerEnd);

        assertFalse(picker.contains("Invoke-RestMethod"));
        assertFalse(picker.contains("Invoke-WebRequest"));
        assertFalse(picker.contains("Method = \"POST\""));
        assertFalse(picker.contains("submitTransaction"));
        assertFalse(picker.contains("setFaab"));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(picker));
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
        throw new IOException("BF-933 test could not locate " + relativePath);
    }
}
