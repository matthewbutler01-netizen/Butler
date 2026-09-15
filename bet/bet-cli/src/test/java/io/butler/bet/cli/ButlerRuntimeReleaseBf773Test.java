package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRuntimeReleaseBf773Test {

    @Test
    void runtimeBuilderPinsExactCleanHeadAndBf769SourceBase() throws Exception {
        String script = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(script.contains("rev-parse', '--verify', 'HEAD^{commit}"));
        assertTrue(script.contains("status', '--porcelain=v1', '--untracked-files=all"));
        assertTrue(script.contains("tracked or untracked worktree changes are present"));
        assertTrue(script.contains("Join-Path $scriptDir 'butler-release-bundle.ps1'"));
        assertTrue(script.contains("& $sourceBuilder -Force"));
        assertTrue(script.contains("Butler-source-{0}.zip"));
        assertFalse(script.contains("Copy-Item -LiteralPath $repoRoot"));
    }

    @Test
    void runtimeBuilderAddsOnlyPreparedJarLibraryAndRemovesGradleToolchain() throws Exception {
        String script = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(script.contains(":bet:bet-cli:installDist"));
        assertTrue(script.contains("bet\\bet-cli\\build\\install\\bet-cli\\lib"));
        assertTrue(script.contains("Get-ChildItem -LiteralPath $installLib -File -Filter '*.jar'"));
        assertTrue(script.contains("expected exactly one bet-cli application JAR"));
        assertTrue(script.contains("installDist library contains non-JAR files"));
        assertTrue(script.contains("Remove-Item -LiteralPath $packagedGradleDir -Recurse -Force"));
        assertTrue(script.contains("Remove-Item -LiteralPath $packagedUnixGradlew -Force"));
        assertTrue(script.contains("BF-773 governed runtime release shim"));
        assertTrue(script.contains("exit /b 23"));
        assertTrue(script.contains("CODE_PLUS_PREBUILT_READ_RUNTIME_NO_RUNTIME_DATA"));
    }

    @Test
    void runtimeAcceptanceVerifiesChecksumFailClosedShimAndExternalData() throws Exception {
        String script = source("scripts/butler-runtime-packaged-launch-acceptance.ps1");

        assertTrue(script.contains("Get-FileHash -LiteralPath $RuntimeZip -Algorithm SHA256"));
        assertTrue(script.contains("current HEAD runtime artifact is absent"));
        assertTrue(script.contains("& $releaseBuilder -Force"));
        assertTrue(script.contains("governed runtime gradlew.bat shim is missing"));
        assertTrue(script.contains("unsupported packaged Gradle task did not fail closed"));
        assertTrue(script.contains("expected exactly one bet-cli application JAR"));
        assertTrue(script.contains("$start.EnvironmentVariables['BUTLER_APP_DATA_DIR'] = $dataDir"));
        assertTrue(script.contains("butler-release-security-check.ps1"));
        assertTrue(script.contains("GRADLE_TOOLCHAIN_ABSENT; SQLITE_RUNTIME_DATA_EXTERNAL"));
        assertFalse(script.contains("POST /refresh"));
        assertFalse(script.contains("Copy-Item -LiteralPath $databasePath"));
        assertFalse(script.contains("Remove-Item -LiteralPath $databasePath"));
    }

    @Test
    void runtimeScriptsRemainAsciiOnly() throws Exception {
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
