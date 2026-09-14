package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellCoreBf751Test {
    @Test
    void preservedCoresWarmSequentiallyWithOneBoundedLeagueReadBeforePublicListener() throws Exception {
        String source = source("scripts/butler-app-shell-core.ps1");
        String warmup = between(source, "function Invoke-PreservedCoreWarmup {", "function Stop-OwnedProcessTree {");

        assertTrue(warmup.contains("BUTLER_APP_CORE_POOL_WARMUP -ceq '0'"));
        assertTrue(warmup.contains("http://127.0.0.1:$BackendPort/league"));
        assertTrue(warmup.contains("$request.Method = 'GET'"));
        assertTrue(warmup.contains("$request.Timeout = 3000"));
        assertTrue(warmup.contains("$request.ReadWriteTimeout = 3000"));
        assertTrue(warmup.contains("$request.Proxy = $null"));
        assertTrue(warmup.contains("$request.KeepAlive = $false"));
        assertTrue(warmup.contains("Write-Warning"));
        assertFalse(warmup.contains("/refresh"));
        assertFalse(warmup.contains("/team"));
        assertFalse(warmup.contains("/waivers"));
        assertFalse(warmup.contains("BeginInvoke"));
        assertFalse(warmup.contains("Start-Job"));
        assertFalse(warmup.contains("-Parallel"));

        String waitCall = "Wait-PreservedCore -BackendPort $backendPort -Process $process";
        String warmCall = "Invoke-PreservedCoreWarmup -BackendPort $backendPort";
        int wait = source.indexOf(waitCall);
        int warm = source.indexOf(warmCall);
        int publicListener = source.indexOf("$requestPool.Open()", warm);
        assertTrue(wait >= 0 && warm > wait && publicListener > warm,
            "BF-751 must warm each core after health and before the public pool listener opens");
        assertEquals(1, occurrences(source, warmCall));
    }

    @Test
    void warmupIsBestEffortAndDoesNotChangeReadWriteBoundaries() throws Exception {
        String source = source("scripts/butler-app-shell-core.ps1");
        String warmup = between(source, "function Invoke-PreservedCoreWarmup {", "function Stop-OwnedProcessTree {");

        assertTrue(warmup.contains("catch {"));
        assertTrue(warmup.contains("BF-751 preserved-core warmup skipped"));
        assertFalse(warmup.contains("throw 'BF-751"));
        assertFalse(warmup.contains("throw \"BF-751"));
        assertFalse(warmup.contains("POST"));
        assertFalse(warmup.contains("Sleeper"));
        assertFalse(warmup.contains("refresh"));
    }

    @Test
    void productionPowerShellSourceRemainsAsciiOnly() throws Exception {
        byte[] bytes = Files.readAllBytes(locate("scripts/butler-app-shell-core.ps1"));
        for (byte value : bytes) {
            assertTrue((value & 0xff) <= 0x7f, "BF-751 PowerShell source must remain ASCII-only");
        }
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while (true) {
            int index = source.indexOf(needle, offset);
            if (index < 0) return count;
            count++;
            offset = index + needle.length();
        }
    }

    private static String between(String source, String begin, String end) {
        int start = source.indexOf(begin);
        int finish = source.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0 || finish <= start) {
            throw new IllegalStateException("BF-751 test could not locate warmup source boundaries");
        }
        return source.substring(start, finish);
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(locate(relativePath), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-751 test could not locate " + relativePath);
    }
}
