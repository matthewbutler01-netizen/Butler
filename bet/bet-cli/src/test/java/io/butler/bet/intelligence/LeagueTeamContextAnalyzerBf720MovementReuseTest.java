package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamContextAnalyzerBf720MovementReuseTest {

    @Test
    void readinessRetainsTheExactMoverEvidenceItClassified() {
        LocalDate previous = LocalDate.of(2026, 9, 1);
        LocalDate latest = LocalDate.of(2026, 9, 8);
        var mover = new LeagueValueMoverAnalyzer.Mover(
            "team-1", "Team One", "player-1", "Player One", "WR", "CHI",
            previous, 100.0, latest, 120.0, 20.0);
        var evidence = new LeagueValueMoverAnalyzer.MoverReport(
            "league-1", "source-1", previous, latest, 2, 1, 1, List.of(mover));

        var readiness = LeagueMovementReadinessAnalyzer.from(evidence);

        assertEquals(LeagueMovementReadinessAnalyzer.Readiness.PARTIAL, readiness.readiness());
        assertEquals(1, readiness.comparablePlayers());
        assertEquals(1, readiness.missingPlayers());
        assertSame(evidence, readiness.movementEvidence());
    }

    @Test
    void compatibilityConstructorLeavesEvidenceAbsent() {
        var readiness = new LeagueMovementReadinessAnalyzer.ReadinessReport(
            "league-1", "source-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8),
            2, 2, 0, LeagueMovementReadinessAnalyzer.Readiness.READY);

        assertEquals(null, readiness.movementEvidence());
    }

    @Test
    void teamContextPrefersRetainedEvidenceAndKeepsCompatibilityFallback() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueTeamContextAnalyzer.java");

        assertTrue(source.contains("var movementEvidence = movementReadiness.movementEvidence();"));
        assertTrue(source.contains("movementEvidence == null"));
        assertTrue(source.contains("? movement.analyze(health.leagueId(), health.source())"));
        assertTrue(source.contains(": movement.summarize(movementEvidence)"));
    }

    @Test
    void moverWindowPathDoesNotPreReadLeagueBeforeDatedAnalysis() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueValueMoverAnalyzer.java");
        int methodStart = source.indexOf("public MoverReport analyze(String leagueId, String source)");
        int datedStart = source.indexOf("public MoverReport analyze(String leagueId, String source,", methodStart + 1);
        String method = source.substring(methodStart, datedStart);

        int window = method.indexOf("var window = windows.latestWindow(normalizedSource);");
        int delegate = method.indexOf("return analyze(normalizedLeagueId, normalizedSource,");
        int leagueRead = method.indexOf("var league = leagues.analyze(normalizedLeagueId);");
        assertTrue(window >= 0);
        assertTrue(delegate > window);
        assertTrue(leagueRead > delegate,
            "the successful window path must delegate before performing the no-window league read");
    }

    @Test
    void teamMovementExposesEvidenceSummarizationWithoutChangingStandaloneEntryPoints() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/TeamValueMovementAnalyzer.java");

        assertTrue(source.contains("public MovementReport analyze(String leagueId)"));
        assertTrue(source.contains("public MovementReport analyze(String leagueId, String source)"));
        assertTrue(source.contains("public MovementReport summarize(LeagueValueMoverAnalyzer.MoverReport report)"));
        assertTrue(source.contains("Objects.requireNonNull(report, \"report must not be null\")"));
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
        throw new IOException("BF-720 test could not locate " + relativePath);
    }
}
