package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerDetailProfileReuseBf886Test {

    @Test
    void playerDetailBuildsGovernedProfileExactlyOnce() throws Exception {
        String detail = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerLeaguePlayerDetailCli.java");

        assertTrue(detail.contains("var profileReport = options.season() == null"));
        assertTrue(detail.contains("var ageReport = ageProduction.analyze(profileReport);"));
        assertFalse(detail.contains("ageProduction.analyze(options.leagueId()"),
            "BF-886 must not restore a second league-wide profile analysis through ageProduction");
    }

    @Test
    void ageProductionCanReuseAnExistingProfileWithoutChangingEvidenceBuild() throws Exception {
        String analyzer = source(
            "bet/bet-cli/src/main/java/io/butler/bet/intelligence/LeagueAgeProductionContextAnalyzer.java");

        assertTrue(analyzer.contains(
            "LeaguePlayerEvidenceProfileAnalyzer.PlayerEvidenceProfileReport profile) throws SQLException"));
        assertTrue(analyzer.contains(
            "return build(Objects.requireNonNull(profile, \"profile must not be null\"));"));
        assertTrue(analyzer.contains(
            "production.findLatestByPlayerIdsAndSeasonAndSource("));
        assertFalse(analyzer.contains(
            "production.findLatest(age.playerId(), profile.season(), profile.productionSource())"),
            "BF-906 must keep BF-886 profile reuse without restoring per-player production reads");
    }

    @Test
    void playerDetailRemainsReadOnlyAndFailClosed() throws Exception {
        String detail = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerLeaguePlayerDetailCli.java");

        assertTrue(detail.contains("player detail evidence dimensions reference different leagues"));
        assertTrue(detail.contains("player detail evidence dimensions reference different seasons"));
        assertTrue(detail.contains("player detail evidence dimensions use different age as-of dates"));
        assertTrue(detail.contains("player must resolve exactly once in league age/production evidence"));
        assertFalse(detail.contains("Sleeper"));
        assertFalse(detail.contains("POST"));
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
        throw new IOException("BF-886 test could not locate " + relativePath);
    }
}
