package io.butler.bet.cli;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Long-lived read-only Butler JVM worker. BF-739 proved help-only reuse; BF-740 adds only the two
 * exact app-core read surfaces required by My Team and League. The worker remains single-requested
 * by its owning preserved core and exposes no generic CLI execution surface.
 */
public final class ButlerReadOnlyJvmWorker {
    static final String READY = "READY\tBF739\t1";
    static final String BYE = "BYE\tBF739";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern LEAGUE_ID = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ButlerReadOnlyJvmWorker() {}

    public static void main(String[] args) throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter protocol = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        serve(input, protocol);
    }

    static void serve(BufferedReader input, PrintWriter protocol) throws IOException {
        serve(input, protocol, ButlerReadOnlyJvmWorker::execute);
    }

    static void serve(BufferedReader input, PrintWriter protocol, CommandExecutor executor) throws IOException {
        protocol.println(READY);
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("QUIT")) {
                protocol.println(BYE);
                return;
            }

            CommandRequest request = parse(line);
            if (request == null) {
                reject(protocol,
                    "BF-740 BLOCKED: worker accepts only HELP<TAB><request-id>, "
                        + "LEAGUE_OVERVIEW<TAB><request-id><TAB><league-id>, "
                        + "TEAM_BUNDLE<TAB><request-id><TAB><league-id>, or QUIT.");
                continue;
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

    private static CommandRequest parse(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length == 2
            && fields[0].equals("HELP")
            && REQUEST_ID.matcher(fields[1]).matches()) {
            return new CommandRequest(Operation.HELP, fields[1], null);
        }
        if (fields.length == 3
            && REQUEST_ID.matcher(fields[1]).matches()
            && LEAGUE_ID.matcher(fields[2]).matches()) {
            if (fields[0].equals("LEAGUE_OVERVIEW")) {
                return new CommandRequest(Operation.LEAGUE_OVERVIEW, fields[1], fields[2]);
            }
            if (fields[0].equals("TEAM_BUNDLE")) {
                return new CommandRequest(Operation.TEAM_BUNDLE, fields[1], fields[2]);
            }
        }
        return null;
    }

    private static Execution execute(CommandRequest request) {
        return switch (request.operation()) {
            case HELP -> executeCaptured(() -> ButlerCommandRouter.main(new String[] {"help"}));
            case LEAGUE_OVERVIEW -> executeCaptured(() -> ButlerCommandRouter.main(
                new String[] {"league", "overview", request.leagueId()}));
            case TEAM_BUNDLE -> executeCaptured(() -> ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main(
                new String[] {request.leagueId(), "--team-bundle"}));
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
        TEAM_BUNDLE
    }

    record CommandRequest(Operation operation, String requestId, String leagueId) {}

    @FunctionalInterface
    interface CommandExecutor {
        Execution execute(CommandRequest request);
    }

    record Execution(int exitCode, String stdout, String stderr) {}
}
