package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardScriptTest {

    @Test
    void bindsOnlyToExplicitIpv4LoopbackOnDeterministicDefaultPort() throws Exception {
        String script = script();
        assertTrue(script.contains("[int]$Port = 8080"));
        assertTrue(script.contains("[System.Net.IPAddress]::Parse(\"127.0.0.1\")"));
        assertTrue(script.contains("[System.Net.Sockets.TcpListener]::new($loopback, $Port)"));
        assertTrue(script.contains("http://127.0.0.1:$Port/"));
        assertFalse(script.contains("IPAddress]::Any"));
        assertFalse(script.contains("0.0.0.0"));
    }

    @Test
    void usesOnlyExistingReadOnlyGovernedDashboardSources() throws Exception {
        String script = script();
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary"));
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit"));
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverComparisonBundle"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverSnapshotSync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverProductionHydration"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverAvailabilitySync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("sleeper-live-waiver-next-decision-cycle.ps1"));
    }

    @Test
    void escapesDynamicValuesAndPinsBrowserSecurityHeaders() throws Exception {
        String script = script();
        assertTrue(script.contains("[System.Net.WebUtility]::HtmlEncode"));
        assertTrue(script.contains("Cache-Control: no-store"));
        assertTrue(script.contains("X-Content-Type-Options: nosniff"));
        assertTrue(script.contains("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"));
        assertFalse(script.contains("<script"));
        assertFalse(script.contains("https://"));
    }

    @Test
    void rendersHumanReadableRecommendationBeforeTechnicalCodes() throws Exception {
        String script = script();
        assertTrue(script.contains("Current Butler recommendation"));
        assertTrue(script.contains("Ready to act"));
        assertTrue(script.contains("What to do"));
        assertTrue(script.contains("player-name"));
        assertTrue(script.contains("player-meta"));
        assertTrue(script.contains("Sleeper ID"));
        assertTrue(script.contains("Butler verified the decision"));
        assertTrue(script.contains("Technical details"));
        assertTrue(script.contains("READ ONLY."));
    }

    @Test
    void lifecycleInstructionsCannotPresentBlockedStatesAsPermissionToAct() throws Exception {
        String script = script();
        assertTrue(script.contains("ActionTitle=\"No action needed\""));
        assertTrue(script.contains("ActionTitle=\"Wait for Sleeper\""));
        assertTrue(script.contains("ActionTitle=\"Consider refreshing first\""));
        assertTrue(script.contains("ActionTitle=\"Stop here\""));
        assertTrue(script.contains("Do not submit this add/drop again"));
        assertTrue(script.contains("Do not make this move from the displayed audit"));
    }

    @Test
    void formatsEvidenceAgeWithoutChangingSixHourPolicy() throws Exception {
        String script = script();
        assertTrue(script.contains("function Format-Age"));
        assertTrue(script.contains("sec ago"));
        assertTrue(script.contains("min ago"));
        assertTrue(script.contains("hr ago"));
        assertTrue(script.contains("hr $minutes min ago"));
        assertTrue(script.contains("$threshold = 21600L"));
        assertTrue(script.contains("$thresholdHours = [math]::Round($threshold / 3600, 1)"));
        assertTrue(script.contains("BF-635 refresh-warning threshold seconds:"));
    }

    @Test
    void myTeamUsesExactBf610RosterLinesWithoutRankingOrIdentityInference() throws Exception {
        String script = script();
        assertTrue(script.contains("function Invoke-ButlerReadOnlyRosterContext"));
        assertTrue(script.contains("function ConvertTo-RosterPlayerView"));
        assertTrue(script.contains("function Get-RosterPlayers"));
        assertTrue(script.contains("rosterSlot="));
        assertTrue(script.contains("mapping="));
        assertTrue(script.contains("name="));
        assertTrue(script.contains("pos="));
        assertTrue(script.contains("nflTeam="));
        assertTrue(script.contains("ButlerPlayer"));
        assertTrue(script.contains("This is BF-610's BF-623-verified target roster"));
        assertTrue(script.contains("not rankings or lineup advice"));
        assertFalse(script.contains("lineup optimization"));
    }

    @Test
    void myTeamGroupsForDisplayAndPreservesRosterStatus() throws Exception {
        String script = script();
        assertTrue(script.contains("$positions = @(\"QB\", \"RB\", \"WR\", \"TE\")"));
        assertTrue(script.contains("Starter -"));
        assertTrue(script.contains("Reserve"));
        assertTrue(script.contains("Taxi"));
        assertTrue(script.contains("Bench"));
        assertTrue(script.contains("Other / unmapped position"));
        assertTrue(script.contains("/team"));
        assertTrue(script.contains("My Team"));
    }

    @Test
    void waiverBoardUsesExactBf616ShortlistAndPreservesSourceOrder() throws Exception {
        String script = script();
        assertTrue(script.contains("function Invoke-ButlerReadOnlyWaiverBoard"));
        assertTrue(script.contains("function ConvertTo-WaiverCandidateView"));
        assertTrue(script.contains("function Get-WaiverCandidates"));
        assertTrue(script.contains("candidate-supported-comparators="));
        assertTrue(script.contains("eligible-comparators="));
        assertTrue(script.contains("Authorized shortlist total / historical / newcomer:"));
        assertTrue(script.contains("parsed BF-616 shortlist count"));
        assertTrue(script.contains("Cards remain in BF-616 deterministic display order"));
        assertTrue(script.contains("NOT A RANKING"));
        assertFalse(script.contains("Sort-Object"));
    }

    @Test
    void waiverBoardKeepsDescriptiveEvidenceNonnumericAndNonRanking() throws Exception {
        String script = script();
        assertTrue(script.contains("Newcomer review lane &middot; nonnumeric"));
        assertTrue(script.contains("Newcomers remain nonnumeric"));
        assertTrue(script.contains("Market attention is descriptive only"));
        assertTrue(script.contains("Status, injury, depth, and market attention are descriptive only"));
        assertTrue(script.contains("does not rerank candidates"));
        assertTrue(script.contains("weight market/depth/injury"));
        assertTrue(script.contains("score newcomers"));
        assertTrue(script.contains("pick a new winner"));
        assertTrue(script.contains("identify a new drop"));
        assertTrue(script.contains("already-audited current ADD"));
        assertFalse(script.contains("candidate score"));
        assertFalse(script.contains("player value"));
    }

    @Test
    void waiverCandidateDetailResolvesExactAuthorizedSleeperIdOnly() throws Exception {
        String script = script();
        assertTrue(script.contains("function Resolve-WaiverCandidateById"));
        assertTrue(script.contains("$SleeperId -notmatch '^[0-9]+$'"));
        assertTrue(script.contains("Where-Object { $_.SleeperId -ceq $SleeperId }"));
        assertTrue(script.contains("duplicate exact Sleeper id"));
        assertTrue(script.contains("^/waivers/candidate/(?<id>[0-9]+)$"));
        assertTrue(script.contains("Candidate is not in the current BF-616 authorized shortlist"));
        assertFalse(script.contains("Resolve-WaiverCandidateByName"));
        assertFalse(script.contains("candidate.Name -eq"));
    }

    @Test
    void waiverCandidateDetailPreservesComparatorTraceabilityAndNewcomerNonnumericSemantics() throws Exception {
        String script = script();
        assertTrue(script.contains("function ConvertTo-WaiverCandidateDetailHtml"));
        assertTrue(script.contains("Candidate-supported comparators:"));
        assertTrue(script.contains("Eligible comparators:"));
        assertTrue(script.contains("Historical directional traceability comes only from Butler's frozen governed comparison method"));
        assertTrue(script.contains("Newcomer review remains explicitly nonnumeric"));
        assertTrue(script.contains("does not fabricate a production score or rank this player"));
        assertTrue(script.contains("Back to Waiver Board"));
        assertTrue(script.contains("READ ONLY &middot; EXACT ID ONLY."));
    }

    @Test
    void waiverTargetIdentityUsesExistingBf623FieldsAndReconcilesRawIds() throws Exception {
        String script = script();
        assertTrue(script.contains("function Get-WaiverTargetView"));
        assertTrue(script.contains("Binding gate state:"));
        assertTrue(script.contains("Bound Sleeper league:"));
        assertTrue(script.contains("Bound roster / role:"));
        assertTrue(script.contains("Bound display/team:"));
        assertTrue(script.contains("Sleeper league / target roster:"));
        assertTrue(script.contains("$gate -cne \"BOUND_TARGET_LIVE_VERIFIED\""));
        assertTrue(script.contains("$role -cne \"OWNER\""));
        assertTrue(script.contains("verified league/roster identity disagrees with BF-616 raw comparison target"));
        assertTrue(script.contains("Human = \"$leagueName | $identityName | roster $rosterId\""));
        assertTrue(script.contains("$identityName = $displayName"));
        assertTrue(script.contains("Get-HeaderHtml -Target $target.Human -Active \"waivers\""));
        assertTrue(script.contains("Raw Sleeper league / roster:"));
        assertTrue(script.contains("Raw comparison identity:"));
        assertTrue(script.contains("BF-623 target gate:"));
        assertTrue(script.contains("BF-623 role:"));
        assertTrue(script.contains("BF-648 BLOCKED"));
    }

    @Test
    void navigationIncludesReadOnlyWaiverBoardAndCandidateRoutes() throws Exception {
        String script = script();
        assertTrue(script.contains("href=\"/waivers\">Waiver Board"));
        assertTrue(script.contains("$path -eq \"/waivers\""));
        assertTrue(script.contains("ConvertTo-WaiverHtml -Bundle $waiverBundle"));
        assertTrue(script.contains("Waiver Board: http://127.0.0.1:$Port/waivers"));
        assertTrue(script.contains("View governed details"));
        assertTrue(script.contains("ConvertTo-WaiverCandidateDetailHtml -Bundle $waiverBundle -Candidate $candidate"));
        assertTrue(script.contains("Candidate detail: http://127.0.0.1:$Port/waivers/candidate/<exact-sleeper-id>"));
    }

    @Test
    void fixesDecisionRecordSpacingAndKeepsExactAuditData() throws Exception {
        String script = script();
        assertTrue(script.contains("lineage-copy strong,.lineage-copy span{display:block}"));
        assertTrue(script.contains("class=\"lineage-copy\""));
        assertTrue(script.contains("Audit ID:"));
        assertTrue(script.contains("Captured UTC:"));
        assertTrue(script.contains("Telemetry UTC:"));
        assertTrue(script.contains("raw-guard"));
    }

    @Test
    void healthEndpointIsLocalAndNativeExitCodeRemainsAuthoritative() throws Exception {
        String script = script();
        assertTrue(script.contains("$path -eq \"/health\""));
        assertTrue(script.contains("{\"status\":\"ok\",\"service\":\"butler-dashboard\",\"bind\":\"127.0.0.1\"}"));
        assertTrue(script.contains("$ErrorActionPreference = \"Continue\""));
        assertTrue(script.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(script.contains("if ($exitCode -ne 0)"));
        assertTrue(script.contains("$ErrorActionPreference = $previousPreference"));
    }

    private static String script() throws IOException {
        return Files.readString(locateScript());
    }

    private static Path locateScript() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-643/BF-644/BF-645/BF-646/BF-647/BF-648/BF-649 test could not locate scripts/butler-dashboard.ps1");
    }
}
