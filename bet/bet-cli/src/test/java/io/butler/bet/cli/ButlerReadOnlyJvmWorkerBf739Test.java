package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReadOnlyJvmWorkerBf739Test {
    @Test
    void repeatedHelpRequestsShareOneSessionAndPreserveIsolatedOutput() throws Exception {
        StringWriter output = new StringWriter();
        try (BufferedReader input = new BufferedReader(new StringReader("HELP\tfirst\nHELP\tsecond\nQUIT\n"));
             PrintWriter protocol = new PrintWriter(output, true)) {
            ButlerReadOnlyJvmWorker.serve(input, protocol);
        }

        List<String> lines = output.toString().lines().toList();
        assertEquals(4, lines.size());
        assertEquals(ButlerReadOnlyJvmWorker.READY, lines.get(0));
        assertEquals(ButlerReadOnlyJvmWorker.BYE, lines.get(3));

        String[] first = result(lines.get(1), "first");
        String[] second = result(lines.get(2), "second");
        assertEquals("0", first[2]);
        assertEquals("0", second[2]);

        String firstStdout = decode(first[3]);
        String secondStdout = decode(second[3]);
        assertFalse(firstStdout.isBlank());
        assertEquals(firstStdout, secondStdout);
        assertTrue(firstStdout.contains("Governed lineup evidence:"));
        assertEquals("", decode(first[4]));
        assertEquals("", decode(second[4]));
    }

    @Test
    void malformedOrUnauthorizedMessagesFailClosedWithoutEndingWorker() throws Exception {
        StringWriter output = new StringWriter();
        try (BufferedReader input = new BufferedReader(new StringReader("RUN\trequest-1\nHELP\tbad request id\nHELP\tgood-id\nQUIT\n"));
             PrintWriter protocol = new PrintWriter(output, true)) {
            ButlerReadOnlyJvmWorker.serve(input, protocol);
        }

        List<String> lines = output.toString().lines().toList();
        assertEquals(5, lines.size());
        assertEquals(ButlerReadOnlyJvmWorker.READY, lines.get(0));
        assertReject(lines.get(1));
        assertReject(lines.get(2));
        assertEquals("0", result(lines.get(3), "good-id")[2]);
        assertEquals(ButlerReadOnlyJvmWorker.BYE, lines.get(4));
    }

    private static String[] result(String line, String requestId) {
        String[] fields = line.split("\\t", -1);
        assertEquals(5, fields.length);
        assertEquals("RESULT", fields[0]);
        assertEquals(requestId, fields[1]);
        return fields;
    }

    private static void assertReject(String line) {
        String[] fields = line.split("\\t", -1);
        assertEquals(2, fields.length);
        assertEquals("REJECT", fields[0]);
        assertTrue(decode(fields[1]).contains("accepts only HELP"));
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}