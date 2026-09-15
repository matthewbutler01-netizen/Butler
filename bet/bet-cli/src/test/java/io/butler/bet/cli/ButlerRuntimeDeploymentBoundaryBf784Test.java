package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRuntimeDeploymentBoundaryBf784Test {

    @Test
    void readmeDefinesRuntimeZipAsCodeOnlyDeploymentOverExistingGovernedData() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("### Deployment boundary"));
        assertTrue(readme.contains("code/runtime-only deployment or update material for a machine that already has governed Butler runtime data"));
        assertTrue(readme.contains("It is not a complete fresh-machine installer"));
        assertTrue(readme.contains("does not contain, export, restore, or recreate `butler.db`"));
        assertTrue(readme.contains("target machine already has a governed external Butler data directory"));
        assertTrue(readme.contains("%LOCALAPPDATA%\\Butler\\data"));
        assertTrue(readme.contains("absolute external `BUTLER_APP_DATA_DIR`"));
    }

    @Test
    void leagueConfigurationIsNotDocumentedAsDataRecreation() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("Supplying `-LeagueId` selects which persisted Butler league the app should use"));
        assertTrue(readme.contains("league UUID does not recreate that league's database or evidence"));
        assertTrue(readme.contains("machine that already has governed Butler runtime data but has no saved league selection"));
        assertTrue(readme.contains(".\\scripts\\butler-app.cmd -LeagueId <butler-league-id>"));
        assertTrue(readme.contains("This configuration selects existing persisted Butler state; it does not create or restore the league database."));
        assertFalse(readme.contains("On a fresh host, or after deliberately resetting the saved league selection"));
    }

    @Test
    void migrationAndPortableDataTransferRemainSeparateObjectives() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("BF-770 `scripts\\butler-migrate-runtime-data.ps1` remains available only for moving a legacy Butler database"));
        assertTrue(readme.contains("It is not a portable backup/export/restore or cross-machine transfer design."));
        assertTrue(readme.contains("Portable Butler data backup/restore and fresh-machine data transfer are intentionally deferred to a separate future objective."));
        assertTrue(readme.contains("does not execute a Butler or Sleeper transaction write"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-784 test could not locate " + relativePath);
    }
}
