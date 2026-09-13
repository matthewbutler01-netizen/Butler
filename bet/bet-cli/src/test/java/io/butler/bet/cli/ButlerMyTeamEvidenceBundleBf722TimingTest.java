package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMyTeamEvidenceBundleBf722TimingTest {

    @Test
    void timingMarkerIsStableMachineReadableAndCoversAllSixEvidenceStages() {
        Map<String, Long> timings = Map.of(
            ButlerMyTeamEvidenceBundleCli.ROSTER_CONTEXT, 11L,
            ButlerMyTeamEvidenceBundleCli.TEAM_CONTEXT, 22L,
            ButlerMyTeamEvidenceBundleCli.ROSTER_STRENGTH, 33L,
            ButlerMyTeamEvidenceBundleCli.POSITIONAL_PRESSURE, 44L,
            ButlerMyTeamEvidenceBundleCli.TEAM_POSTURE, 55L,
            ButlerMyTeamEvidenceBundleCli.FUTURE_CAPITAL, 66L);

        assertEquals(
            "===BUTLER_TEAM_TIMING:database_ms=1;target_ms=2;analysis_wall_ms=77;"
                + "roster_context_ms=11;team_context_ms=22;roster_strength_ms=33;"
                + "positional_pressure_ms=44;team_posture_ms=55;future_capital_ms=66;"
                + "render_ms=3;total_ms=88===",
            ButlerMyTeamEvidenceBundleCli.timingMarker(1L, 2L, 77L, timings, 3L, 88L));
    }

    @Test
    void timingMarkerRemainsOutsideTheSixGovernedSections() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        int emitRoster = bundle.indexOf("emit(ROSTER_CONTEXT, rosterContext);");
        int emitTeam = bundle.indexOf("emit(TEAM_CONTEXT, teamContext);", emitRoster);
        int emitStrength = bundle.indexOf("emit(ROSTER_STRENGTH, rosterStrength);", emitTeam);
        int emitPressure = bundle.indexOf("emit(POSITIONAL_PRESSURE, positionalPressure);", emitStrength);
        int emitPosture = bundle.indexOf("emit(TEAM_POSTURE, teamPosture);", emitPressure);
        int emitCapital = bundle.indexOf("emit(FUTURE_CAPITAL, futureCapital);", emitPosture);
        int timing = bundle.indexOf("System.out.println(timingMarker(", emitCapital);
        int boundary = bundle.indexOf("Boundary: BF-699", timing);

        assertTrue(emitRoster >= 0 && emitTeam > emitRoster && emitStrength > emitTeam);
        assertTrue(emitPressure > emitStrength && emitPosture > emitPressure && emitCapital > emitPosture);
        assertTrue(timing > emitCapital, "timing diagnostics must remain outside all six governed sections");
        assertTrue(boundary > timing);
        assertTrue(bundle.contains("System.nanoTime()"));
        assertTrue(bundle.contains("ConcurrentHashMap"));
        assertTrue(bundle.contains("POST_ROSTER_WORKERS = 5"));
        assertFalse(bundle.contains("create_transaction"));
        assertFalse(bundle.contains("submitTransaction"));
    }

    @Test
    void oneCommandAcceptanceRunsDiagnosticOnlyAfterTheBf688Benchmark() throws Exception {
        String acceptance = source("scripts/butler-acceptance.ps1");
        String diagnostic = source("scripts/butler-team-stage-diagnostic.ps1");

        int loadCheck = acceptance.indexOf("& $loadCheck -BaseUrl");
        int teamDiagnostic = acceptance.indexOf("& $teamDiagnostic", loadCheck);
        assertTrue(loadCheck >= 0 && teamDiagnostic > loadCheck,
            "stage diagnostic must run after BF-688 so it cannot contaminate benchmark totals");

        assertTrue(diagnostic.contains("app-league.txt"));
        assertTrue(diagnostic.contains("build\\install\\bet-cli\\lib"));
        assertTrue(diagnostic.contains("io.butler.bet.cli.ButlerMyTeamEvidenceBundleCli"));
        assertTrue(diagnostic.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(diagnostic.contains("^===BUTLER_TEAM_TIMING:"));
        assertTrue(diagnostic.contains("outside BF-688"));
        assertFalse(diagnostic.contains("/refresh" + "'"));
        assertFalse(diagnostic.contains("create_transaction"));
        assertFalse(diagnostic.contains("submitTransaction"));

        byte[] encoded = diagnostic.getBytes(StandardCharsets.US_ASCII);
        assertEquals(diagnostic, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-722 test could not locate " + relativePath);
    }
}
