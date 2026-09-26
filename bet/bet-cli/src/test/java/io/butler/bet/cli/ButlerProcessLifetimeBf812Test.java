package io.butler.bet.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerProcessLifetimeBf812Test {

    @Test
    void windowsLauncherUsesDedicatedSupervisorAndNarrowWatchdog() throws Exception {
        String launcher = source("scripts/butler-app.cmd");
        String supervisor = source("scripts/butler-app-supervisor.ps1");
        String watchdog = source("scripts/butler-child-tree-watchdog.ps1");

        assertTrue(launcher.contains("butler-app-supervisor.ps1"));
        assertTrue(supervisor.contains("butler-app-guard.ps1"));
        assertTrue(supervisor.contains("butler-child-tree-watchdog.ps1"));
        assertTrue(watchdog.contains("ParentProcessId = $GuardPid"));
        assertTrue(watchdog.contains("*butler-app-shell-core.ps1*"));
        assertTrue(watchdog.contains("/PID $corePid /T /F"));
        assertFalse(watchdog.contains("*java.exe*"));
        assertFalse(watchdog.contains("ButlerReadOnlyJvmWorker"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void watchdogKillsOrphanedPreservedCoreTreeAfterGuardExit() throws Exception {
        Path watchdog = locate("scripts/butler-child-tree-watchdog.ps1");
        Path temp = Files.createTempDirectory("bf812-watchdog-");
        Path coreScript = temp.resolve("butler-app-shell-core.ps1");
        Path guardScript = temp.resolve("guard.ps1");
        Path supervisorScript = temp.resolve("supervisor.ps1");
        Path guardPidFile = temp.resolve("guard.pid");
        Path corePidFile = temp.resolve("core.pid");

        Files.writeString(coreScript, """
            param([Parameter(Mandatory = $true)][string]$PidFile)
            [IO.File]::WriteAllText($PidFile, [string]$PID, [Text.Encoding]::ASCII)
            while ($true) { Start-Sleep -Seconds 1 }
            """, StandardCharsets.UTF_8);

        Files.writeString(guardScript, """
            param(
                [Parameter(Mandatory = $true)][string]$CoreScript,
                [Parameter(Mandatory = $true)][string]$CorePidFile
            )
            $ps = Join-Path $env:SystemRoot 'System32\\WindowsPowerShell\\v1.0\\powershell.exe'
            $start = [Diagnostics.ProcessStartInfo]::new()
            $start.FileName = $ps
            $start.Arguments = \"-NoLogo -NoProfile -ExecutionPolicy Bypass -File `\"$CoreScript`\" -PidFile `\"$CorePidFile`\"\"
            $start.UseShellExecute = $false
            $start.CreateNoWindow = $true
            $core = [Diagnostics.Process]::Start($start)
            if ($null -eq $core) { throw 'core start failed' }
            while ($true) { Start-Sleep -Seconds 1 }
            """, StandardCharsets.UTF_8);

        Files.writeString(supervisorScript, """
            param(
                [Parameter(Mandatory = $true)][string]$GuardScript,
                [Parameter(Mandatory = $true)][string]$CoreScript,
                [Parameter(Mandatory = $true)][string]$GuardPidFile,
                [Parameter(Mandatory = $true)][string]$CorePidFile
            )
            $ps = Join-Path $env:SystemRoot 'System32\\WindowsPowerShell\\v1.0\\powershell.exe'
            $start = [Diagnostics.ProcessStartInfo]::new()
            $start.FileName = $ps
            $start.Arguments = \"-NoLogo -NoProfile -ExecutionPolicy Bypass -File `\"$GuardScript`\" -CoreScript `\"$CoreScript`\" -CorePidFile `\"$CorePidFile`\"\"
            $start.UseShellExecute = $false
            $start.CreateNoWindow = $true
            $guard = [Diagnostics.Process]::Start($start)
            if ($null -eq $guard) { throw 'guard start failed' }
            [IO.File]::WriteAllText($GuardPidFile, [string]$guard.Id, [Text.Encoding]::ASCII)
            while ($true) { Start-Sleep -Seconds 1 }
            """, StandardCharsets.UTF_8);

        String powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        Process supervisor = new ProcessBuilder(
            powershell, "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", supervisorScript.toString(),
            "-GuardScript", guardScript.toString(),
            "-CoreScript", coreScript.toString(),
            "-GuardPidFile", guardPidFile.toString(),
            "-CorePidFile", corePidFile.toString())
            .redirectErrorStream(true)
            .start();

        Process watchdogProcess = null;
        long guardPid = -1;
        long corePid = -1;
        try {
            waitForFile(guardPidFile, Duration.ofSeconds(10));
            waitForFile(corePidFile, Duration.ofSeconds(10));
            guardPid = Long.parseLong(Files.readString(guardPidFile).trim());
            corePid = Long.parseLong(Files.readString(corePidFile).trim());

            long supervisorTicks = processStartTicks(powershell, supervisor.pid());
            long guardTicks = processStartTicks(powershell, guardPid);

            watchdogProcess = new ProcessBuilder(
                powershell, "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", watchdog.toString(),
                "-SupervisorPid", Long.toString(supervisor.pid()),
                "-SupervisorStartTicks", Long.toString(supervisorTicks),
                "-GuardPid", Long.toString(guardPid),
                "-GuardStartTicks", Long.toString(guardTicks))
                .redirectErrorStream(true)
                .start();

            Process guard = new ProcessBuilder("taskkill.exe", "/PID", Long.toString(guardPid), "/F")
                .redirectErrorStream(true)
                .start();
            assertTrue(guard.waitFor(10, TimeUnit.SECONDS));

            assertTrue(waitUntilDead(corePid, Duration.ofSeconds(10)), "BF-812 watchdog left the preserved core alive");
            assertTrue(watchdogProcess.waitFor(10, TimeUnit.SECONDS), "BF-812 watchdog did not exit after cleanup");
            String watchdogOutput = new String(watchdogProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, watchdogProcess.exitValue(), "BF-812 watchdog output:\n" + watchdogOutput);
        }
        finally {
            if (watchdogProcess != null && watchdogProcess.isAlive()) {
                watchdogProcess.destroyForcibly();
            }
            if (guardPid > 0) {
                new ProcessBuilder("taskkill.exe", "/PID", Long.toString(guardPid), "/T", "/F").start().waitFor(5, TimeUnit.SECONDS);
            }
            if (corePid > 0) {
                new ProcessBuilder("taskkill.exe", "/PID", Long.toString(corePid), "/T", "/F").start().waitFor(5, TimeUnit.SECONDS);
            }
            if (supervisor.isAlive()) {
                supervisor.destroyForcibly();
                supervisor.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static long processStartTicks(String powershell, long pid) throws Exception {
        Process process = new ProcessBuilder(
            powershell, "-NoLogo", "-NoProfile", "-Command",
            "(Get-Process -Id " + pid + ").StartTime.ToUniversalTime().Ticks")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return Long.parseLong(output);
    }

    private static void waitForFile(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(path) && Files.size(path) > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IOException("Timed out waiting for " + path);
    }

    private static boolean waitUntilDead(long pid, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
                return true;
            }
            Thread.sleep(100);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
    }

    private static String source(String relativePath) throws IOException {
        return Files.readString(locate(relativePath), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IOException("BF-812 test could not locate " + relativePath);
    }
}
