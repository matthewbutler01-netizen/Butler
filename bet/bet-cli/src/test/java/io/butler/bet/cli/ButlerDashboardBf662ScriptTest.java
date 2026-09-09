package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf662ScriptTest {

    @Test
    void noTransactionToRevalidateIsSatisfied() throws Exception {
        String script = script();
        assertTrue(script.contains("$Bf629 -eq \"NO_TRANSACTION_TO_REVALIDATE\""));
        assertTrue(script.contains("elseif ($Bf629 -eq \"NO_TRANSACTION_TO_REVALIDATE\") { \"No transaction to revalidate\" }"));
    }

    @Test
    void transactionBearingSatisfiedStatesRemainRecognized() throws Exception {
        String script = script();
        assertTrue(script.contains("$Bf629 -eq \"LIVE_ACTIONABLE_VERIFIED\""));
        assertTrue(script.contains("$Bf629 -eq \"AUDITED_TRANSACTION_COMPLETE\""));
        assertTrue(script.contains("$Bf629 -eq \"AUDITED_TRANSACTION_PENDING\""));
        assertTrue(script.contains("\"Live roster check passed\""));
        assertTrue(script.contains("\"Completed transaction verified\""));
        assertTrue(script.contains("\"Pending transaction verified\""));
    }

    @Test
    void unknownOrUnsafeRosterStatesRemainNotGreen() throws Exception {
        String script = script();
        assertTrue(script.contains("else { \"Live roster safety gate not green\" }"));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-662 test could not locate scripts/butler-dashboard.ps1");
    }
}
