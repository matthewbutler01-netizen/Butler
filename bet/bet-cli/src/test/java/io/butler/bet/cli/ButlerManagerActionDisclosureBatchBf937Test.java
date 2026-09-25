package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerActionDisclosureBatchBf937Test {

    @Test
    void playerSearchExposesExactCompareAndScoutActions() throws Exception {
        String transform = source("scripts/butler-app-bf882-player-search-transform.ps1");

        assertTrue(transform.contains("$teamHrefId = [System.Uri]::EscapeDataString([string]$player.OwnerTeamId)"));
        assertTrue(transform.contains("$positionHref = [System.Uri]::EscapeDataString([string]$player.Position)"));
        assertTrue(transform.contains("href=`\"/compare?left=$hrefId&q=$positionHref`\">Compare this player</a>"));
        assertTrue(transform.contains("href=`\"/franchise?id=$teamHrefId`\">Scout franchise</a>"));
        assertTrue(transform.contains("href=`\"/player?id=$hrefId`\">View Player Detail</a>"));
    }

    @Test
    void decisionHistoryKeepsNewestVisibleAndOlderRecordsDisclosed() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("$olderCards = ''"));
        assertTrue(history.contains("if ($isNewest) {"));
        assertTrue(history.contains("$cards += $cardHtml"));
        assertTrue(history.contains("$olderCards += $cardHtml"));
        assertTrue(history.contains("View older decisions ($olderCount)"));
        assertTrue(history.contains("<div class=\"history-list\">$cards</div>$olderHistoryHtml"));
        assertTrue(history.contains("<summary>Decision details</summary>"));
        assertTrue(history.contains("<summary>Show decision details</summary>"));
    }

    @Test
    void tradePickerOpensPlayersAndCollapsesPicksByDefault() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(trade.contains("$groupOpen = if ($group.Class -ceq 'players') { ' open' } else { '' }"));
        assertTrue(trade.contains("<details class=`\"asset-group $($group.Class)`\"$groupOpen>"));
        assertTrue(trade.contains("<summary class=`\"asset-group-head`\">"));
        assertTrue(trade.contains(".asset-group>summary{cursor:pointer;list-style:none}"));
        assertTrue(trade.contains("Class = 'players'; Label = 'Players'"));
        assertTrue(trade.contains("Class = 'picks'; Label = 'Draft picks'"));
    }

    @Test
    void journeyCoversNewManagerActionsAndDisclosureState() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("'Compare this player','Scout franchise'"));
        assertTrue(journey.contains("<details class=\"asset-group players\" open"));
        assertTrue(journey.contains("<details class=\"asset-group picks\""));
        assertTrue(journey.contains("Decision History older-record disclosure"));
        assertTrue(journey.contains("View older decisions"));
    }

    @Test
    void batchRemainsReadOnlyAndAscii() throws Exception {
        String search = source("scripts/butler-app-bf882-player-search-transform.ps1");
        String history = source("scripts/butler-decision-history.ps1");
        String trade = source("scripts/butler-trade-lab.ps1");

        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(search));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(history));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(trade));

        for (String forbidden : new String[]{
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab"
        }) {
            assertFalse(section(search, "function ConvertTo-PlayerSearchHtml", "function Get-PlayerDetailRequestId").contains(forbidden));
            assertFalse(section(history, "function ConvertTo-DecisionHistoryHtml", "function Invoke-DecisionHistoryHtml").contains(forbidden));
            assertFalse(section(trade, "function ConvertTo-TradeAssetCheckboxes", "function ConvertTo-TradeLabHtml").contains(forbidden));
        }
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, start);
        assertTrue(start >= 0 && end > start, "BF-937 source section is missing");
        return text.substring(start, end);
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
        throw new IOException("BF-937 test could not locate " + relativePath);
    }
}
