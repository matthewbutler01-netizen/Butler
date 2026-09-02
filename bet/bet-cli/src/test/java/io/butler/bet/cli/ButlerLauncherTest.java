package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
    void advertisesCompositeProfileAndPlayerEvidenceSyntax() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLauncher.printTeamProfileUsage();
            ButlerLauncher.printPlayerEvidenceReadinessUsage();
        } finally {
            System.setOut(original);
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(
            "butler league team-profile <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
        assertTrue(output.contains(
            "butler league player-evidence-readiness <league-id> [season] [--minimum-profile-as-of YYYY-MM-DD]"));
    }
}
