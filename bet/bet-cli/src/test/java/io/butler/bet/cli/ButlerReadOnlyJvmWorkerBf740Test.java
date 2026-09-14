package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReadOnlyJvmWorkerBf740Test {
    @Test
    void exactCoreOperationsAreFramedAndCarryOnlyValidatedLeagueId() throws Exception {
        List<ButlerReadOnlyJvmWorker.CommandRequest> requests = new ArrayList<>();
        ButlerReadOnlyJvmWorker.CommandExecutor executor = request -> {
            requests.add(request);
            String output = request.operation() + ":" + request.leagueId();
            return new ButlerReadOnlyJvmWorker.Execution(0, output, "");
        };

        StringWriter output = new StringWriter();
        try (BufferedReader input = new BufferedReader(new StringReader(
                "LEAGUE_OVERVIEW\toverview-1\t123456789012345678\n"
                    + "TEAM_BUNDLE\tteam-1\t123456789012345678\n"
                    + "QUIT\n"));
             PrintWriter protocol = new PrintWriter(output, true)) {
            ButlerReadOnlyJvmWorker.serve(input, protocol, executor);
        }

        List<String> lines = output.toString().lines().toList();
        assertEquals(4, lines.size());
        assertEquals(ButlerReadOnlyJvmWorker.READY, lines.get(0));
        assertEquals(ButlerReadOnlyJvmWorker.BYE, lines.get(3));

        assertResult(lines.get(1), "overview-1", "LEAGUE_OVERVIEW:123456789012345678");
        assertResult(lines.get(2), "team-1", "TEAM_BUNDLE:123456789012345678");

        assertEquals(2, requests.size());
        assertEquals(ButlerReadOnlyJvmWorker.Operation.LEAGUE_OVERVIEW, requests.get(0).operation());
        assertEquals("123456789012345678", requests.get(0).leagueId());
        assertEquals(ButlerReadOnlyJvmWorker.Operation.TEAM_BUNDLE, requests.get(1).operation());
        assertEquals("123456789012345678", requests.get(1).leagueId());
    }

    @Test
    void unknownOperationsAndBadArgumentShapesFailClosedWithoutExecution() throws Exception {
        List<ButlerReadOnlyJvmWorker.CommandRequest> requests = new ArrayList<>();
        ButlerReadOnlyJvmWorker.CommandExecutor executor = request -> {
            requests.add(request);
            return new ButlerReadOnlyJvmWorker.Execution(0, "unexpected", "");
        };

        StringWriter output = new StringWriter();
        try (BufferedReader input = new BufferedReader(new StringReader(
                "RUN\tr1\t123456\n"
                    + "LEAGUE_OVERVIEW\tr2\tnot-a-league\n"
                    + "TEAM_BUNDLE\tr3\t123456\textra\n"
                    + "LEAGUE_OVERVIEW\tbad request\t123456\n"
                    + "QUIT\n"));
             PrintWriter protocol = new PrintWriter(output, true)) {
            ButlerReadOnlyJvmWorker.serve(input, protocol, executor);
        }

        List<String> lines = output.toString().lines().toList();
        assertEquals(6, lines.size());
        assertEquals(ButlerReadOnlyJvmWorker.READY, lines.get(0));
        for (int index = 1; index <= 4; index++) {
            assertReject(lines.get(index));
        }
        assertEquals(ButlerReadOnlyJvmWorker.BYE, lines.get(5));
        assertTrue(requests.isEmpty());
    }

    private static void assertResult(String line, String requestId, String expectedOutput) {
        String[] fields = line.split("\\t", -1);
        assertEquals(5, fields.length);
        assertEquals("RESULT", fields[0]);
        assertEquals(requestId, fields[1]);
        assertEquals("0", fields[2]);
        assertEquals(expectedOutput, decode(fields[3]));
        assertEquals("", decode(fields[4]));
    }

    private static void assertReject(String line) {
        String[] fields = line.split("\\t", -1);
        assertEquals(2, fields.length);
        assertEquals("REJECT", fields[0]);
        String message = decode(fields[1]);
        assertTrue(message.contains("LEAGUE_OVERVIEW"));
        assertTrue(message.contains("TEAM_BUNDLE"));
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
