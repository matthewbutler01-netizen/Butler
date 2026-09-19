package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperClient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Long-lived read-only Butler JVM worker. BF-739 proved JVM reuse, BF-740 added the exact My Team
 * and League reads, and BF-742 adds only the exact dashboard reads needed to share one worker per
 * preserved core. The protocol exposes no generic CLI or task execution surface.
 */
public final class ButlerReadOnlyJvmWorker {
    static final String READY = "READY\tBF739\t1";
    static final String BYE = "BYE\tBF739";
    private static final String SLEEPER_TRANSPORT_PREWARM_ENV = "BUTLER_APP_SLEEPER_TRANSPORT_PREWARM";
    private static final Duration SLEEPER_TRANSPORT_PREWARM_TIMEOUT = Duration.ofSeconds(2);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern LEAGUE_ID = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern AUDIT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private ButlerReadOnlyJvmWorker() {}

    public static void main(String[] args) throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter protocol = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        serve(input, protocol);
    }

    static void serve(BufferedReader input, PrintWriter protocol) throws IOException {
        serve(
            input,
            protocol,
            ButlerReadOnlyJvmWorker::execute,
            ButlerReadOnlyJvmWorker::prewarmSharedSleeperTransportBestEffort);
    }

    static void serve(BufferedReader input, PrintWriter protocol, CommandExecutor executor) throws IOException {
        serve(input, protocol, executor, () -> {});
    }

    static void serve(
        BufferedReader input,
        PrintWriter protocol,
        CommandExecutor executor,
        WarmupAction warmup) throws IOException {
        protocol.println(READY);
        boolean warmupAttempted = false;
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("QUIT")) {
                protocol.println(BYE);
                return;
            }

            CommandRequest request = parse(line);
            if (request == null) {
                reject(protocol,
                    "BF-742 BLOCKED: worker accepts only HELP<TAB><request-id>, "
                        + "LEAGUE_OVERVIEW, TEAM_BUNDLE, LATEST_SUMMARY, LATEST_SUMMARY_DIAGNOSTIC, TARGET_VERIFY_DIAGNOSTIC, WAIVER_DASHBOARD_BUNDLE, or MATCHUP_BUNDLE "
                        + "with <request-id><TAB><league-id>, EXPLANATION_LOOKUP with "
                        + "<request-id><TAB><league-id><TAB><audit-id>, or QUIT.");
                continue;
            }

            if (!warmupAttempted && request.operation() != Operation.HELP) {
                warmupAttempted = true;
                warmup.run();
            }

            Execution execution = executor.execute(request);
            protocol.print("RESULT\t");
            protocol.print(request.requestId());
            protocol.print('\t');
            protocol.print(execution.exitCode());
            protocol.print('\t');
            protocol.print(encode(execution.stdout()));
            protocol.print('\t');
            protocol.println(encode(execution.stderr()));
        }
    }

    static boolean sleeperTransportPrewarmEnabled(String value) {
        return !"0".equals(value);
    }

    private static void prewarmSharedSleeperTransportBestEffort() {
        if (!sleeperTransportPrewarmEnabled(System.getenv(SLEEPER_TRANSPORT_PREWARM_ENV))) {
            return;
        }
        SleeperClient.prewarmSharedTransportBestEffort(SLEEPER_TRANSPORT_PREWARM_TIMEOUT);
    }

    private static CommandRequest parse(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length == 2
            && fields[0].equals("HELP")
            && REQUEST_ID.matcher(fields[1]).matches()) {
            return new CommandRequest(Operation.HELP, fields[1], null, null);
        }
        if (fields.length == 3
            && REQUEST_ID.matcher(fields[1]).matches()
            && LEAGUE_ID.matcher(fields[2]).matches()) {
            return switch (fields[0]) {
                case "LEAGUE_OVERVIEW" -> new CommandRequest(Operation.LEAGUE_OVERVIEW, fields[1], fields[2], null);
                case "TEAM_BUNDLE" -> new CommandRequest(Operation.TEAM_BUNDLE, fields[1], fields[2], null);
                case "LATEST_SUMMARY" -> new CommandRequest(Operation.LATEST_SUMMARY, fields[1], fields[2], null);
                case "LATEST_SUMMARY_DIAGNOSTIC" ->
                    new CommandRequest(Operation.LATEST_SUMMARY_DIAGNOSTIC, fields[1], fields[2], null);
                case "TARGET_VERIFY_DIAGNOSTIC" ->
                    new CommandRequest(Operation.TARGET_VERIFY_DIAGNOSTIC, fields[1], fields[2], null);
                case "WAIVER_DASHBOARD_BUNDLE" ->
                    new CommandRequest(Operation.WAIVER_DASHBOARD_BUNDLE, fields[1], fields[2], null);
                case "MATCHUP_BUNDLE" ->
                    new CommandRequest(Operation.MATCHUP_BUNDLE, fields[1], fields[2], null);
                default -> null;
            };
        }
        if (fields.length == 4
            && fields[0].equals("EXPLANATION_LOOKUP")
            && REQUEST_ID.matcher(fields[1]).matches()
            && LEAGUE_ID.matcher(fields[2]).matches()
            && AUDIT_ID.matcher(fields[3]).matches()) {
            return new CommandRequest(Operation.EXPLANATION_LOOKUP, fields[1], fields[2], fields[3]);
        }
        return null;
    }

    private static Execution execute(CommandRequest request) {
        return switch (request.operation()) {
            case HELP -> executeCaptured(() -> ButlerCommandRouter.main(new String[] {"help"}));
            case LEAGUE_OVERVIEW -> executeCaptured(() -> ButlerCommandRouter.main(
                new String[] {"league", "overview", request.leagueId()}));
            case TEAM_BUNDLE -> executeCapturedWithExitCode(() -> ButlerMyTeamEvidenceBundleCli.runEmbedded(
                new String[] {request.leagueId()}));
            case LATEST_SUMMARY -> executeCapturedWithExitCode(() ->
                ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runEmbedded(
                    new String[] {request.leagueId()}));
            case LATEST_SUMMARY_DIAGNOSTIC -> executeCapturedWithExitCode(() ->
                ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runDiagnosticEmbedded(
                    new String[] {request.leagueId()}));
            case TARGET_VERIFY_DIAGNOSTIC -> executeCapturedWithExitCode(() ->
                ButlerSleeperPersonalTargetVerificationDiagnosticCli.runEmbedded(
                    new String[] {request.leagueId()}));
            case WAIVER_DASHBOARD_BUNDLE -> executeCapturedWithExitCode(() ->
                ButlerWaiverDashboardEvidenceBundleCli.runEmbedded(
                    new String[] {request.leagueId()}));
            case MATCHUP_BUNDLE -> executeCapturedWithExitCode(() ->
                ButlerWeeklyMatchupEvidenceBundleCli.runEmbedded(
                    new String[] {request.leagueId()}));
            case EXPLANATION_LOOKUP -> executeCapturedWithExitCode(() ->
                ButlerSleeperLiveWaiverGovernedExplanationLookupCli.runEmbedded(
                    new String[] {request.leagueId(), request.argument()}));
        };
    }

    private static Execution executeCaptured(Runnable command) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            try {
                command.run();
                return new Execution(0, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
            }
            catch (RuntimeException | Error failure) {
                failure.printStackTrace(capturedErr);
                return new Execution(1, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
            }
            finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    static Execution executeCapturedWithExitCode(ExitCodeCommand command) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            try {
                int exitCode = command.run();
                return new Execution(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
            }
            catch (RuntimeException | Error failure) {
                failure.printStackTrace(capturedErr);
                return new Execution(1, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
            }
            finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static void reject(PrintWriter protocol, String message) {
        protocol.print("REJECT\t");
        protocol.println(encode(message));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    enum Operation {
        HELP,
        LEAGUE_OVERVIEW,
        TEAM_BUNDLE,
        LATEST_SUMMARY,
        LATEST_SUMMARY_DIAGNOSTIC,
        TARGET_VERIFY_DIAGNOSTIC,
        WAIVER_DASHBOARD_BUNDLE,
        MATCHUP_BUNDLE,
        EXPLANATION_LOOKUP
    }

    record CommandRequest(Operation operation, String requestId, String leagueId, String argument) {}

    @FunctionalInterface
    interface CommandExecutor {
        Execution execute(CommandRequest request);
    }

    @FunctionalInterface
    interface WarmupAction {
        void run();
    }

    @FunctionalInterface
    interface ExitCodeCommand {
        int run();
    }

    record Execution(int exitCode, String stdout, String stderr) {}
}
