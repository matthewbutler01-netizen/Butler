package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf697HealthFastPathTest {

    @Test
    void healthIsAnsweredBeforeHeavyRequestModulesLoad() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        int healthBranch = worker.indexOf("if ($path -eq '/health')");
        int tradeHostLoad = worker.indexOf(". $TradeHost", healthBranch);
        int tradeLabLoad = worker.indexOf(". $TradeLab", healthBranch);
        int historyLoad = worker.indexOf(". $History", healthBranch);
        int detailLoad = worker.indexOf(". $Detail", healthBranch);
        int refreshLoad = worker.indexOf(". $DecisionRefresh", healthBranch);

        assertTrue(healthBranch >= 0, "BF-697 requires an exact /health branch");
        assertTrue(tradeHostLoad > healthBranch, "Trade host must load after /health fast-path");
        assertTrue(tradeLabLoad > healthBranch, "Trade lab must load after /health fast-path");
        assertTrue(historyLoad > healthBranch, "History must load after /health fast-path");
        assertTrue(detailLoad > healthBranch, "Decision detail must load after /health fast-path");
        assertTrue(refreshLoad > healthBranch, "Decision refresh must load after /health fast-path");
        assertTrue(worker.contains("\"service\":\"butler-app-shell\""));
    }

    @Test
    void windowsOverridesAreRestoredAfterModulesLoad() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        int tradeLabLoad = worker.indexOf(". $TradeLab");
        int parserSnapshot = worker.indexOf("$requestParserOverride = (Get-Item Function:\\ConvertFrom-TradeRequestTarget).ScriptBlock");
        int selectionSnapshot = worker.indexOf("$selectionSetOverride = (Get-Item Function:\\Get-TradeSelectionSet).ScriptBlock");
        int parserRestore = worker.indexOf("Set-Item -Path Function:\\ConvertFrom-TradeRequestTarget -Value $requestParserOverride");
        int selectionRestore = worker.indexOf("Set-Item -Path Function:\\Get-TradeSelectionSet -Value $selectionSetOverride");

        assertTrue(parserSnapshot >= 0 && parserSnapshot < tradeLabLoad,
                "BF-697 must snapshot the Windows request parser override before module loading");
        assertTrue(selectionSnapshot >= 0 && selectionSnapshot < tradeLabLoad,
                "BF-697 must snapshot the empty-selection override before module loading");
        assertTrue(parserRestore > tradeLabLoad,
                "BF-697 must restore the Windows request parser override after module loading");
        assertTrue(selectionRestore > tradeLabLoad,
                "BF-697 must restore the empty-selection override after module loading");
    }

    @Test
    void refreshAndNormalRoutesStillLoadExistingModules() throws Exception {
        String worker = source("scripts/butler-app-request-worker.ps1");

        assertTrue(worker.contains(". $TradeHost"));
        assertTrue(worker.contains(". $TradeLab"));
        assertTrue(worker.contains(". $History"));
        assertTrue(worker.contains(". $Detail"));
        assertTrue(worker.contains(". $DecisionRefresh"));
        assertTrue(worker.contains("if ($parts[0] -eq 'POST')"));
        assertTrue(worker.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(worker.contains("Consume-RefreshToken -State $RefreshState -SubmittedToken $submittedToken"));
        assertTrue(worker.chars().allMatch(ch -> ch <= 127));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-697 health fast-path test could not locate " + relativePath);
    }
}
