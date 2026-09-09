package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardBf661ScriptTest {

    @Test
    void noTransactionUsesExplicitStateAndAbsentAbsentIdentity() throws Exception {
        String script = script();
        assertTrue(script.contains("$isNoTransaction = $decisionState -ceq \"NO_TRANSACTION_TO_ACT_ON\""));
        assertTrue(script.contains("NO_TRANSACTION_TO_ACT_ON must not contain audited ADD/DROP Sleeper ids"));
        assertTrue(script.contains("$lookupIdsRaw -cne \"none / none\""));
        assertTrue(script.contains("BF-653 no-transaction audited ADD/DROP ids must be absent"));
        assertTrue(script.contains("Headline=\"No governed transaction\""));
    }

    @Test
    void transactionBearingPathRetainsExactAddDropChecks() throws Exception {
        String script = script();
        assertTrue(script.contains("BF-654 BLOCKED: current audited ADD/DROP exact Sleeper ids are missing"));
        assertTrue(script.contains("'^(?<add>[0-9]+)\\s*/\\s*(?<drop>[0-9]+)$'"));
        assertTrue(script.contains("$lookupAddId -cne $add.SleeperId -or $lookupDropId -cne $drop.SleeperId"));
        assertTrue(script.contains("BF-654 BLOCKED: BF-653 audited ADD/DROP ids disagree with current audited transaction"));
    }

    @Test
    void unknownRecommendationStatesFailClosed() throws Exception {
        String script = script();
        assertTrue(script.contains("BF-661 BLOCKED: unsupported current governed decision state"));
        assertTrue(script.contains("BF-661 BLOCKED: unsupported dashboard decision state"));
    }

    @Test
    void noTransactionRendersExplanationWithoutSyntheticMoveCardsOrCapturePrompt() throws Exception {
        String script = script();
        assertTrue(script.contains("$movesSection = \"\""));
        assertTrue(script.contains("if ($transactionStates -ccontains $state)"));
        assertTrue(script.contains("elseif ($state -cne \"NO_TRANSACTION_TO_ACT_ON\" -and $state -cne \"NO_AUDITED_DECISION\")"));
        assertTrue(script.contains("if ($Explanation.Ready)"));
        assertTrue(script.contains("Active = $false"));
        assertTrue(script.contains("BF-653 explanation type:"));
        assertTrue(script.contains("Audited ADD / DROP Sleeper IDs:"));
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
        throw new IOException("BF-661 test could not locate scripts/butler-dashboard.ps1");
    }
}
