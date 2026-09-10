package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppLauncherBf669PortAwareTest {

    @Test
    void launcherPreflightsRequestedPortAndRecognizesButlerShell() throws Exception {
        String script = script();

        assertTrue(script.contains("function Get-AppPortState"));
        assertTrue(script.contains("http://127.0.0.1:$RequestedPort/health"));
        assertTrue(script.contains("$request.Proxy = $null"));
        assertTrue(script.contains("[System.Net.Sockets.TcpListener]::new("));
        assertTrue(script.contains("$probe.Start()"));
        assertTrue(script.contains("catch [System.Net.Sockets.SocketException]"));
        assertTrue(script.contains("return \"FREE\""));
        assertTrue(script.contains("return \"OCCUPIED_BUTLER\""));
        assertTrue(script.contains("return \"OCCUPIED_OTHER\""));
        assertTrue(script.contains("\"service\"\\s*:\\s*\"butler-app-shell\""));
        assertFalse(script.contains("[System.Net.WebExceptionStatus]::ConnectFailure"));
    }

    @Test
    void failedHealthProbeFallsThroughToActualLoopbackBindCheck() throws Exception {
        String script = script();

        assertTrue(script.contains("if ($null -ne $_.Exception.Response)"));
        assertTrue(script.contains("if ($null -ne $response)"));
        assertTrue(script.contains("[System.Net.IPAddress]::Parse(\"127.0.0.1\")"));
        assertTrue(script.contains("try { $probe.Stop() } catch {}"));
    }

    @Test
    void liveRunMarkerRecognizesBusyButlerWithoutDependingOnHealthTiming() throws Exception {
        String script = script();

        assertTrue(script.contains("running-port-{0}.txt"));
        assertTrue(script.contains("function Test-LiveButlerRunState"));
        assertTrue(script.contains("function Write-ButlerRunState"));
        assertTrue(script.contains("function Remove-OwnButlerRunState"));
        assertTrue(script.contains("Get-Process -Id $markerPid -ErrorAction SilentlyContinue"));
        assertTrue(script.contains("$process.StartTime.ToUniversalTime().Ticks"));
        assertTrue(script.contains("$liveRunState -or $ownedByButlerProcess"));
        assertTrue(script.contains("$portState = \"OCCUPIED_BUTLER\""));
        assertTrue(script.contains("Butler is already starting on port $Port"));
        assertTrue(script.contains("finally {\n    Remove-OwnButlerRunState\n}"));
    }

    @Test
    void occupiedListenerCanBeIdentifiedAsThisButlerCheckoutWithoutKillingIt() throws Exception {
        String script = script();

        assertTrue(script.contains("function Test-PortOwnedByButlerProcess"));
        assertTrue(script.contains("Get-NetTCPConnection -LocalPort $RequestedPort -State Listen"));
        assertTrue(script.contains("Where-Object { $_.LocalAddress -ceq \"127.0.0.1\" }"));
        assertTrue(script.contains("Get-CimInstance Win32_Process -Filter \"ProcessId = $ownerPid\""));
        assertTrue(script.contains("$launcherPath = [IO.Path]::GetFullPath($MyInvocation.MyCommand.Path)"));
        assertTrue(script.contains("$appShellPath = [IO.Path]::GetFullPath($appShell)"));
        assertTrue(script.contains("$commandLine.IndexOf($launcherPath, [System.StringComparison]::OrdinalIgnoreCase)"));
        assertTrue(script.contains("$commandLine.IndexOf($appShellPath, [System.StringComparison]::OrdinalIgnoreCase)"));
        assertTrue(script.contains("Listener ownership is only an identity fallback. Failure here must remain fail-closed."));
    }

    @Test
    void duplicateAndForeignPortFailuresAreClearAndNeverKillProcesses() throws Exception {
        String script = script();

        assertTrue(script.contains("Butler is already running on port $Port"));
        assertTrue(script.contains("stop its PowerShell window with Ctrl+C"));
        assertTrue(script.contains("local port $Port is already in use by another process"));
        assertTrue(script.contains("Butler will not stop it automatically"));
        assertTrue(script.contains("-Port <free-port>"));

        assertFalse(script.contains("Stop-Process"));
        assertFalse(script.contains("taskkill"));
        assertFalse(script.contains("Invoke-Expression"));
    }

    @Test
    void launcherRemainsAsciiOnly() throws Exception {
        String script = script();
        byte[] encoded = script.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-app.ps1");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-669 test could not locate scripts/butler-app.ps1");
    }
}
