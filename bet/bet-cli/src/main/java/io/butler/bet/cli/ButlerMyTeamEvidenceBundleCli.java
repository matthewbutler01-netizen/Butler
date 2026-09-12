package io.butler.bet.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only BF-692 composition of the exact BF-668 My Team evidence sources in one JVM.
 * This class adds no analyzer, score, recommendation, mutation, or evidence synthesis.
 */
public final class ButlerMyTeamEvidenceBundleCli {
    static final String ROSTER_CONTEXT = "ROSTER_CONTEXT";
    static final String TEAM_CONTEXT = "TEAM_CONTEXT";
    static final String ROSTER_STRENGTH = "ROSTER_STRENGTH";
    static final String POSITIONAL_PRESSURE = "POSITIONAL_PRESSURE";
    static final String TEAM_POSTURE = "TEAM_POSTURE";
    static final String FUTURE_CAPITAL = "FUTURE_CAPITAL";

    private static final Pattern PROVIDER_SEASON =
        Pattern.compile("(?m)^Provider season/status/leg:\\s+(\\d+)/");

    private ButlerMyTeamEvidenceBundleCli() {}

    public static void main(String[] args) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("Error: butlerMyTeamEvidenceBundle requires one Butler league id.");
            System.exit(2);
        }

        String leagueId = args[0].trim();

        String rosterContext = capture(() ->
            ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main(new String[]{leagueId}));
        int season = providerSeason(rosterContext);

        String teamContext = capture(() ->
            ButlerMain.main(new String[]{"league", "team-context", leagueId}));
        String rosterStrength = capture(() ->
            ButlerLeagueRosterStrengthCli.main(new String[]{"league", "roster-strength", leagueId}));
        String positionalPressure = capture(() ->
            ButlerLeaguePositionalPressureCli.main(new String[]{"league", "positional-pressure", leagueId}));
        String teamPosture = capture(() ->
            ButlerLeagueTeamPostureCli.main(new String[]{"league", "team-posture", leagueId, Integer.toString(season)}));
        String futureCapital = capture(() ->
            ButlerLeagueFutureCapitalCli.main(new String[]{"league", "future-capital", leagueId}));

        emit(ROSTER_CONTEXT, rosterContext);
        emit(TEAM_CONTEXT, teamContext);
        emit(ROSTER_STRENGTH, rosterStrength);
        emit(POSITIONAL_PRESSURE, positionalPressure);
        emit(TEAM_POSTURE, teamPosture);
        emit(FUTURE_CAPITAL, futureCapital);
        System.out.println("Boundary: BF-692 composes existing read-only My Team evidence only; no Butler or Sleeper write is executed.");
    }

    static int providerSeason(String rosterContext) {
        Matcher matcher = PROVIDER_SEASON.matcher(rosterContext == null ? "" : rosterContext);
        if (!matcher.find()) {
            throw new IllegalStateException("BF-692 BLOCKED: BF-610 roster context is missing provider season.");
        }
        return Integer.parseInt(matcher.group(1));
    }

    static String capture(Runnable command) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            command.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8).stripTrailing();
    }

    static void emit(String name, String body) {
        System.out.println(beginMarker(name));
        if (body != null && !body.isEmpty()) System.out.println(body);
        System.out.println(endMarker(name));
    }

    static String beginMarker(String name) {
        return "===BUTLER_TEAM_BUNDLE:" + name + ":BEGIN===";
    }

    static String endMarker(String name) {
        return "===BUTLER_TEAM_BUNDLE:" + name + ":END===";
    }
}
