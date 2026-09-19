package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerUnifiedManagerNavigationBf870Test {

    private static final List<String> ROUTES = List.of(
        "href=\"/\"",
        "href=\"/team\"",
        "href=\"/matchup\"",
        "href=\"/waivers\"",
        "href=\"/league\"",
        "href=\"/trade\"",
        "href=\"/history\""
    );

    @Test
    void nativeManagerNavsExposeAllSevenDestinationsInCanonicalOrder() throws Exception {
        for (String file : List.of(
            "scripts/butler-app-bf840-weekly-matchup-transform.ps1",
            "scripts/butler-trade-lab-host.ps1",
            "scripts/butler-decision-history.ps1"
        )) {
            String source = source(file);
            int previous = -1;
            for (String route : ROUTES) {
                int next = source.indexOf(route);
                assertTrue(next > previous, file + " missing or misordered " + route);
                previous = next;
            }
        }
    }

    @Test
    void stagedCoreAndTradeNavDefineEveryActiveStateTheyRender() throws Exception {
        String matchup = source("scripts/butler-app-bf840-weekly-matchup-transform.ps1");
        String trade = source("scripts/butler-trade-lab-host.ps1");

        for (String marker : List.of(
            "$dashboardClass",
            "$teamClass",
            "$matchupClass",
            "$waiversClass",
            "$leagueClass",
            "$tradeClass",
            "$historyClass"
        )) {
            assertTrue(matchup.contains(marker + " = if ($Active"), "BF-840 missing active-state marker " + marker);
            assertTrue(trade.contains(marker + " = if ($Active"), "Trade Analyzer missing active-state marker " + marker);
        }
    }

    @Test
    void proxiedNavigationInsertsMissingLinksAtCanonicalAnchors() throws Exception {
        for (String file : List.of(
            "scripts/butler-trade-lab-host.ps1",
            "scripts/butler-decision-history.ps1"
        )) {
            String source = source(file);
            assertTrue(source.contains("href=\"/team\"[^>]*>My Team</a>)"));
            assertTrue(source.contains("'$1<a href=\"/matchup\">Matchup</a>'"));
            assertTrue(source.contains("href=\"/league\"[^>]*>League</a>)"));
            assertTrue(source.contains("'$1<a href=\"/trade\">Trade Analyzer</a>'"));
            assertTrue(source.contains("href=\"/trade\"[^>]*>Trade Analyzer</a>)"));
            assertTrue(source.contains("'$1<a href=\"/history\">History</a>'"));
        }
    }

    @Test
    void navigationChangeRemainsPresentationOnly() throws Exception {
        for (String file : List.of(
            "scripts/butler-app-bf840-weekly-matchup-transform.ps1",
            "scripts/butler-trade-lab-host.ps1",
            "scripts/butler-decision-history.ps1"
        )) {
            String source = source(file);
            assertFalse(source.contains("Method = \"POST\""), file + " introduced POST behavior");
            assertFalse(source.contains("submitTransaction"), file + " introduced transaction behavior");
            assertFalse(source.contains("setFaab"), file + " introduced FAAB behavior");
        }
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
        throw new IOException("BF-870 test could not locate " + relativePath);
    }
}
