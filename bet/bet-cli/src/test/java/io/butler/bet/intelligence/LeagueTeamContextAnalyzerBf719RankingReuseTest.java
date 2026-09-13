package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamContextAnalyzerBf719RankingReuseTest {

    @Test
    void teamContextRanksThePortfolioItAlreadyBuilt() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueTeamContextAnalyzer.java");

        assertEquals(1, occurrences(source, "portfolios.analyze("),
            "team context should build its portfolio exactly once");
        assertTrue(source.contains("FranchiseValueRankingAnalyzer.rank(\n                portfolio, health.minimumAsOfDate())"));
        assertFalse(source.contains("rankings.rank("),
            "team context must not launch a second portfolio-backed ranking analysis");
        assertFalse(source.contains("new FranchiseValueRankingAnalyzer(database)"));
    }

    @Test
    void rankingCompositionPreservesPortfolioOrderRulesAndMinimumDate() {
        LocalDate cutoff = LocalDate.of(2026, 9, 1);
        var portfolio = new TeamAssetPortfolioAnalyzer.PortfolioReport(
            "league", "source", 560.0, 240.0, 2, 0, 2, 0,
            List.of(
                new TeamAssetPortfolioAnalyzer.TeamPortfolio(
                    "alpha", "Alpha", 300.0, 80.0, 1, 0, 1, 0, cutoff, cutoff),
                new TeamAssetPortfolioAnalyzer.TeamPortfolio(
                    "beta", "Beta", 260.0, 160.0, 1, 0, 1, 0, cutoff, cutoff)));

        var report = FranchiseValueRankingAnalyzer.rank(portfolio, cutoff);

        assertEquals(cutoff, report.minimumAsOfDate());
        assertEquals("source", report.source());
        assertEquals(800.0, report.totalAssetValue());
        assertEquals("Beta", report.teams().getFirst().teamName());
        assertEquals(1, report.teams().getFirst().rank());
        assertEquals("Alpha", report.teams().get(1).teamName());
        assertEquals(2, report.teams().get(1).rank());
    }

    @Test
    void rankingCompositionStillFailsClosedForIncompletePortfolio() {
        var portfolio = new TeamAssetPortfolioAnalyzer.PortfolioReport(
            "league", "source", 100.0, 0.0, 1, 1, 0, 0,
            List.of(new TeamAssetPortfolioAnalyzer.TeamPortfolio(
                "alpha", "Alpha", 100.0, 0.0, 1, 1, 0, 0, null, null)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> FranchiseValueRankingAnalyzer.rank(portfolio, null));

        assertTrue(error.getMessage().contains("requires complete asset coverage"));
        assertTrue(error.getMessage().contains("missing-players=1"));
    }

    @Test
    void standaloneRankingEntryPointsRemainIntact() throws Exception {
        String source = source("bet/bet-cli/src/main/java/io/butler/bet/intelligence/FranchiseValueRankingAnalyzer.java");

        assertTrue(source.contains("public RankingReport rank(String leagueId) throws SQLException"));
        assertTrue(source.contains("public RankingReport rank(String leagueId, String source) throws SQLException"));
        assertTrue(source.contains("public RankingReport rank(String leagueId, LocalDate minimumAsOfDate) throws SQLException"));
        assertTrue(source.contains("public RankingReport rank(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException"));
        assertTrue(source.contains("readiness.analyze(leagueId, source, cutoff)"));
        assertTrue(source.contains("requireRankable(readinessReport)"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
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
        throw new IOException("BF-719 test could not locate " + relativePath);
    }
}
