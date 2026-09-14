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
 * BF-739 diagnostic worker proving that Butler can execute a governed read-only command repeatedly
 * inside one JVM. This proof intentionally authorizes global help only; production app-shell
 * routing remains unchanged until a later BF explicitly integrates a worker.
 */
public final class ButlerReadOnlyJvmWorker {
    static final String READY = "READY\tBF739\t1";
    static final String BYE = "BYE\tBF739";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private ButlerReadOnlyJvmWorker() {}

    public static void main(String[] args) throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter protocol = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        serve(input, protocol);
    }

    static void serve(BufferedReader input, PrintWriter protocol) throws IOException {
        protocol.println(READY);
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("QUIT")) {
                protocol.println(BYE);
                return;
            }

            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || !fields[0].equals("HELP") || !REQUEST_ID.matcher(fields[1]).matches()) {
                reject(protocol, "BF-739 BLOCKED: worker accepts only HELP<TAB><request-id> or QUIT.");
                continue;
            }

            Execution execution = executeHelp();
            protocol.print("RESULT\t");
            protocol.print(fields[1]);
            protocol.print('\t');
            protocol.print(execution.exitCode());
            protocol.print('\t');
            protocol.print(encode(execution.stdout()));
            protocol.print('\t');
            protocol.println(encode(execution.stderr()));
        }
    }

    private static Execution executeHelp() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            try {
                ButlerCommandRouter.main(new String[] {"help"});
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

    record Execution(int exitCode, String stdout, String stderr) {}
}