package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupProjectionDeltaBf942Test {

    @Test
    void cliKeepsExistingAssignmentLineAndAddsProjectionDeltaLine() throws Exception {
        String cli = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerAutoFillLineupRecommendationCli.java");

        assertTrue(cli.contains(" | projected="));
        assertTrue(cli.contains(" | action="));
        assertTrue(cli.contains(" projection_delta"));
        assertTrue(cli.contains("assignment.currentProjectedPoints()"));
        assertTrue(cli.contains("assignment.projectedGain()"));
        assertTrue(cli.contains("optionalPoints"));
        assertTrue(cli.contains("UNAVAILABLE"));
    }

    @Test
    void finalTransformReconcilesAndRendersProjectionDeltaEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf942-lineup-projection-delta-transform.ps1");

        assertTrue(transform.contains("projection_delta"));
        assertTrue(transform.contains("CurrentPoints"));
        assertTrue(transform.contains("RecommendedPoints"));
        assertTrue(transform.contains("SlotGain"));
        assertTrue(transform.contains("slot projection gain does not reconcile"));
        assertTrue(transform.contains("slot delta"));
        assertTrue(transform.contains("AutoFill still solves the lineup globally"));
        assertTrue(transform.contains("Compare this swap"));
        assertTrue(transform.contains("UNAVAILABLE"));
    }

    @Test
    void bf942StagesAfterBf941AndBeforeDiagnosticTiming() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf941 = staging.indexOf("& $bf941Transform -CorePath $stagedCore");
        int bf942 = staging.indexOf("& $bf942Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf941 >= 0, "BF-941 staging marker missing");
        assertTrue(bf942 > bf941, "BF-942 must run after BF-941");
        assertTrue(bf857 > bf942, "BF-857 timing must remain after BF-942");
    }

    @Test
    void projectionDeltaTransformIsAscii() throws Exception {
        String transform = source("scripts/butler-app-bf942-lineup-projection-delta-transform.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
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
        throw new IOException("BF-942 test could not locate " + relativePath);
    }
}
