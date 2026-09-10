package io.butler.bet.cli;

import io.butler.bet.intelligence.LeaguePlayerEvidenceReadinessAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLauncherTest {
    @Test
    void recognizesAllSupportedTeamProfileArgumentForms() {
        assertTrue(ButlerLauncher.isSupportedTeamProfile(new String[]{"league", "team-profile", "league-id"}));
        assertTrue(ButlerLauncher.isSupportedTeamProfile(new String[]{"league", "team-profile", "league-id", "source"}));
        assertTrue(ButlerLauncher.isSupportedTeamProfile(new String[]{
            "league", "team-profile", "league-id", "--minimum-as-of", "2026-09-02"}));
        assertTrue(ButlerLauncher.isSupportedTeamProfile(new String[]{
            "league", "team-profile", "league-id", "source", "--minimum-as-of", "2026-09-02"}));

        assertFalse(ButlerLauncher.isSupportedTeamProfile(new String[]{"league", "team-profile"}));
        assertFalse(ButlerLauncher.isSupportedTeamProfile(new String[]{
            "league", "team-profile", "league-id", "--wrong", "2026-09-02"}));
    }

    @Test
    void recognizesEvidenceOverviewArgumentForms() {
        assertTrue(ButlerLauncher.isSupportedEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id"}));
        assertTrue(ButlerLauncher.isSupportedEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id", "2025"}));
        assertFalse(ButlerLauncher.isSupportedEvidenceOverview(new String[]{
            "league", "evidence-overview"}));
        assertFalse(ButlerLauncher.isSupportedEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id", "2025", "extra"}));
    }

    @Test
    void recognizesPlayerEvidenceReadinessArgumentForms() {
        assertTrue(ButlerLauncher.isSupportedPlayerEvidenceReadiness(new String[]{
            "league", "player-evidence-readiness", "league-id"}));
        assertTrue(ButlerLauncher.isSupportedPlayerEvidenceReadiness(new String[]{
            "league", "player-evidence-readiness", "league-id", "2025"}));
        assertTrue(ButlerLauncher.isSupportedPlayerEvidenceReadiness(new String[]{
            "league", "player-evidence-readiness", "league-id", "--minimum-profile-as-of", "2026-09-01"}));
        assertTrue(ButlerLauncher.isSupportedPlayerEvidenceReadiness(new String[]{
            "league", "player-evidence-readiness", "league-id", "2025", "--minimum-profile-as-of", "2026-09-01"}));

        assertFalse(ButlerLauncher.isSupportedPlayerEvidenceReadiness(new String[]{
            "league", "player-evidence-readiness"}));
        assertFalse(ButlerLauncher.isSupportedPlayerEvidenceReadiness(new String[]{
            "league", "player-evidence-readiness", "league-id", "2025", "--wrong", "2026-09-01"}));
    }

    @Test
    void rendersPlayerEvidenceTeamsAsCompactPrimaryAndDetailRowsInStableOrder() {
        var report = new LeaguePlayerEvidenceReadinessAnalyzer.ReadinessReport(
            "league-id", 2025, "sleeper", "nflverse", LocalDate.of(2026, 9, 1),
            List.of(
                new LeaguePlayerEvidenceReadinessAnalyzer.TeamReadiness(
                    "team-alpha", "Alpha", 20, 18, 10, 8, 6, 19,
                    LeaguePlayerEvidenceReadinessAnalyzer.Readiness.PARTIAL),
                new LeaguePlayerEvidenceReadinessAnalyzer.TeamReadiness(
                    "team-zulu", "Zulu", 20, 20, 12, 8, 7, 20,
                    LeaguePlayerEvidenceReadinessAnalyzer.Readiness.READY)));

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLauncher.printPlayerEvidenceReadiness(report);
        } finally {
            System.setOut(original);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);

        String alphaPrimary = "Alpha  PARTIAL  age=18/20 (90.0%)  production=19/20 (95.0%)";
        String zuluPrimary = "Zulu  READY  age=20/20 (100.0%)  production=20/20 (100.0%)";
        assertTrue(output.contains(alphaPrimary));
        assertTrue(output.contains("  details: exact-birth=10  reported-age=8  experience=6  team-id=team-alpha"));
        assertTrue(output.contains(zuluPrimary));
        assertTrue(output.contains("  details: exact-birth=12  reported-age=8  experience=7  team-id=team-zulu"));
        assertTrue(output.indexOf(alphaPrimary) < output.indexOf(zuluPrimary));
        assertFalse(output.contains("Alpha  readiness="));
    }

    @Test
    void advertisesCompositeProfileAndEvidenceSyntax() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLauncher.printTeamProfileUsage();
            ButlerLauncher.printEvidenceOverviewUsage();
            ButlerLauncher.printPlayerEvidenceReadinessUsage();
        } finally {
            System.setOut(original);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(
            "butler league team-profile <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
        assertTrue(output.contains(
            "butler league evidence-overview <league-id> [season]"));
        assertTrue(output.contains(
            "butler league player-evidence-readiness <league-id> [season] [--minimum-profile-as-of YYYY-MM-DD]"));
    }
}
