package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerRecoveryStageContractBf1122Test {

    @Test
    void coreSourceMatchesThreeExactBf884ReplacementInputs() throws Exception {
        // BF-1122: these cross-file anchors broke Windows staging twice.
        // Catch mismatched source templates in the faster Java build gate.
        String core = source("scripts/butler-app-shell-core-single.ps1");
        String bf884 = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");

        for (String name : new String[] {"bf1119LeagueOld", "bf1119TeamOld", "bf1119GenericOld"}) {
            String original = psHereString(bf884, name);
            assertEquals(1, count(core, original), "BF-884 original source must appear exactly once: " + name);
        }
        assertTrue(bf884.contains("function New-ManagerRecoveryPageHtml"));
        assertTrue(bf884.contains("if ($Detail -ceq 'UNVERIFIED_SOURCE')"));
        assertFalse(bf884.contains("-Detail $_.Exception.Message"));
        assertFalse(bf884.contains("-Detail $teamFailure"));
    }

    @Test
    void bf976ConsumesExactlyTheThreeNewBf884PlayerRecoveryLines() throws Exception {
        String bf884 = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");
        String bf976 = source("scripts/butler-app-bf976-player-workflow-safe-stop-transform.ps1");

        for (String[] pair : new String[][]{
                {"Player Detail blocked page", "detailOld"},
                {"Player Search blocked page", "searchOld"},
                {"Player Compare blocked page", "compareOld"}
        }) {
            String output = bf884Replacement(bf884, pair[0]);
            String input = psString(bf976, pair[1]);
            assertEquals(output, input,
                    "BF-976 exact replacement input must match BF-884 output for " + pair[0]);
        }
        assertTrue(bf976.contains("PLAYER_DETAIL_UNVERIFIED"));
        assertTrue(bf976.contains("PLAYER_SEARCH_UNVERIFIED"));
        assertTrue(bf976.contains("PLAYER_COMPARE_UNVERIFIED"));
        assertFalse(bf976.contains("-Message $_.Exception.Message"));
    }

    private static int count(String source, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static String psHereString(String source, String name) {
        Matcher match = Pattern.compile("(?m)^\\$" + Pattern.quote(name)
                + " = @'\\R([^\\r\\n]*)\\R'@\\s*$").matcher(source);
        assertTrue(match.find(), "Missing single-line PowerShell source contract: " + name);
        return match.group(1);
    }

    private static String psString(String source, String name) {
        Matcher match = Pattern.compile("(?m)^\\$" + Pattern.quote(name)
                + " = '([^\\r\\n]*)'\\s*$").matcher(source);
        assertTrue(match.find(), "Missing single-line PowerShell target contract: " + name);
        return match.group(1);
    }

    private static String bf884Replacement(String source, String contract) {
        String pattern = "(?m)^[ \\t]*Contract = '" + Pattern.quote(contract)
                + "'\\R[ \\t]*Old = [^\\r\\n]+\\R[ \\t]*New = '([^\\r\\n]*)'\\s*$";
        Matcher match = Pattern.compile(pattern).matcher(source);
        assertTrue(match.find(), "Missing BF-884 stage output: " + contract);
        return match.group(1);
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
        throw new IOException("BF-1122 cannot find " + relativePath);
    }
}
