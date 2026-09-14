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
    private static final String LEAGUE_ID = "8f5f6f8a-9f8c-4b68-8f2b-7f7cbe64d291";

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
                "LEAGUE_OVERVIEW\toverview-1\t" + LEAGUE_ID + "\n"
                    + "TEAM_BUNDLE\tteam-1\t" + LEAGUE_ID + "\n"
                    + "QUIT\n"));
             PrintWriter protocol = new PrintWriter(output, true)) {
            ButlerReadOnlyJvmWorker.serve(input, protocol, executor);
        }

        List<String> lines = output.toString().lines().toList();
        assertEquals(4, lines.size());
        assertEquals(ButlerReadOnlyJvmWorker.READY, lines.get(0));
        assertEquals(ButlerReadOnlyJvmWorker.BYE, lines.get(3));

        assertResult(lines.get(1), "overview-1", "LEAGUE_OVERVIEW:" + LEAGUE_ID);
        assertResult(lines.get(2), "team-1", "TEAM_BUNDLE:" + LEAGUE_ID);

        assertEquals(2, requests.size());
        assertEquals(ButlerReadOnlyJvmWorker.Operation.LEAGUE_OVERVIEW, requests.get(0).operation());
        assertEquals(LEAGUE_ID, requests.get(0).leagueId());
        assertEquals(ButlerReadOnlyJvmWorker.Operation.TEAM_BUNDLE, requests.get(1).operation());
        assertEquals(LEAGUE_ID, requests.get(1).leagueId());
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
                "RUN\tr1\t" + LEAGUE_ID + "\n"
                    + "LEAGUE_OVERVIEW\tr2\t123456789012345678\n"
                    + "TEAM_BUNDLE\tr3\t" + LEAGUE_ID + "\textra\n"
                    + "LEAGUE_OVERVIEW\tbad request\t" + LEAGUE_ID + "\n"
                    + "TEAM_BUNDLE\tr4\t8F5F6F8A-9F8C-4B68-8F2B-7F7CBE64D291\n"
                    + "QUIT\n"));
             PrintWriter protocol = new PrintWriter(output, true)) {
            ButlerReadOnlyJvmWorker.serve(input, protocol, executor);
        }

        List<String> lines = output.toString().lines().toList();
        assertEquals(7, lines.size());
        assertEquals(ButlerReadOnlyJvmWorker.READY, lines.get(0));
        for (int index = 1; index <= 5; index++) {
            assertReject(lines.get(index));
        }
        assertEquals(ButlerReadOnlyJvmWorker.BYE, lines.get(6));
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
