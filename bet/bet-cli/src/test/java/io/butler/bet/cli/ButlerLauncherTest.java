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
    void advertisesCompositeProfileSyntax() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerLauncher.printTeamProfileUsage();
        } finally {
            System.setOut(original);
        }
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains(
            "butler league team-profile <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
    }
}
