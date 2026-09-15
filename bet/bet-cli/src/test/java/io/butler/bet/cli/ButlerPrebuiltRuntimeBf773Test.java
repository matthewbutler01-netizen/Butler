package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPrebuiltRuntimeBf773Test {

    @Test
    void runtimeBuilderStartsFromExactCleanHeadAndBf769SourceArtifact() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(builder.contains("rev-parse', '--verify', 'HEAD^{commit}"));
        assertTrue(builder.contains("status', '--porcelain', '--untracked-files=all"));
        assertTrue(builder.contains("runtime release requires a clean tracked/untracked worktree"));
        assertTrue(builder.contains("Join-Path $scriptDir 'butler-release-bundle.ps1'"));
        assertTrue(builder.contains("& $sourceBuilder -Force"));
        assertTrue(builder.contains("Butler-source-{0}.zip"));
        assertTrue(builder.contains(":bet:bet-cli:installDist"));
        assertTrue(builder.contains("'--no-daemon'"));
    }

    @Test
    void runtimeBuilderPackagesOnlyExpectedInstallDistJarsAndRemovesGradleToolchain() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(builder.contains("bet\\bet-cli\\build\\install\\bet-cli\\lib"));
        assertTrue(builder.contains("$packagedRuntimePrefix = 'bet/bet-cli/build/install/bet-cli/lib/'"));
        assertTrue(builder.contains("Copy-Item -LiteralPath $jar.FullName"));
        assertTrue(builder.contains("$segments -contains 'build') -and -not $isPackagedRuntimeJar"));
        assertTrue(builder.contains("Remove-Item -LiteralPath $wrapperDir -Recurse -Force"));
        assertTrue(builder.contains("Remove-Item -LiteralPath $unixGradle -Force"));
        assertTrue(builder.contains("runtime release must not contain the Gradle wrapper toolchain"));
        assertTrue(builder.contains("boundary=CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA"));

        assertFalse(builder.contains("Copy-Item -LiteralPath $databasePath"));
        assertFalse(builder.contains("butler.db' -Destination"));
        assertFalse(builder.contains("POST /refresh"));
    }

    @Test
    void runtimeShimAllowsOnlyStartupInstallDistProbeAndFailsClosedOtherwise() throws Exception {
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(builder.contains("if /I not \"%~1\"==\"--no-daemon\" goto :blocked"));
        assertTrue(builder.contains("if /I not \"%~2\"==\":bet:bet-cli:installDist\" goto :blocked"));
        assertTrue(builder.contains("if not \"%~3\"==\"\" goto :blocked"));
        assertTrue(builder.contains("runtime package Gradle shim only authorizes the prebuilt installDist startup probe"));
        assertTrue(builder.contains("exit /b 77"));
        assertTrue(builder.contains("refresh_toolchain=BLOCKED_FAIL_CLOSED_IN_RUNTIME_PACKAGE"));
    }

    @Test
    void packagedAcceptanceVerifiesRuntimeShimExternalDataAndReadOnlySecuritySmoke() throws Exception {
        String acceptance = source("scripts/butler-runtime-packaged-launch-acceptance.ps1");

        assertTrue(acceptance.contains("$runtimeZipExplicit = $PSBoundParameters.ContainsKey('RuntimeZip')"));
        assertTrue(acceptance.contains("Join-Path $scriptDir 'butler-runtime-release-bundle.ps1'"));
        assertTrue(acceptance.contains("Get-FileHash -LiteralPath $RuntimeZip -Algorithm SHA256"));
        assertTrue(acceptance.contains("bet\\bet-cli\\build\\install\\bet-cli\\lib"));
        assertTrue(acceptance.contains("extracted runtime package still contains the Gradle wrapper toolchain"));
        assertTrue(acceptance.contains("governed fail-closed BF-773 startup shim"));
        assertTrue(acceptance.contains("$start.EnvironmentVariables['BUTLER_APP_DATA_DIR'] = $dataDir"));
        assertTrue(acceptance.contains("butler-release-security-check.ps1"));
        assertTrue(acceptance.contains("GRADLE_TOOLCHAIN_ABSENT"));

        assertFalse(acceptance.contains("POST /refresh"));
        assertFalse(acceptance.contains("Copy-Item -LiteralPath $databasePath"));
        assertFalse(acceptance.contains("Remove-Item -LiteralPath $databasePath"));
    }

    @Test
    void bf773WindowsSourcesRemainAsciiOnly() throws Exception {
        for (String path : new String[]{
            "scripts/butler-runtime-release-bundle.ps1",
            "scripts/butler-runtime-packaged-launch-acceptance.ps1"
        }) {
            String text = source(path);
            byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
            assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
        }
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-773 test could not locate " + relativePath);
    }
}
