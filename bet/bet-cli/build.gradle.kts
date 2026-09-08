plugins {
    application
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("io.butler.bet.cli.ButlerCommandRouter")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val butlerAcceptanceTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs Butler acceptance tests tagged with JUnit's 'acceptance' tag."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("acceptance")
    }
}

val historicalLineupSeasonSync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Hydrates provider-observed historical Sleeper lineup evidence for all populated weeks in a season."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerHistoricalLineupSeasonSyncCli")
}

val sleeperCorpusAcquisitionPlan by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Enumerates target-season Sleeper corpus candidates from a known anchor franchise without importing them."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCorpusAcquisitionPlanCli")
}

val sleeperCohortCorpusAcquisitionPlan by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Enumerates target-season Sleeper corpus candidates across all owners represented in an anchor league without importing them."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCohortCorpusAcquisitionPlanCli")
}

val sleeperCohortCorpusHydrate by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Hydrates the complete BF-555 cohort candidate frame using provider lineage without outcome-based selection."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCohortCorpusHydrateCli")
}

val sleeperWeekScoringSourceAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-558 proof audit of Sleeper weekly raw-stat coverage and exact matchup scoring parity."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperWeekScoringSourceAuditCli")
}

val sleeperSeasonProviderPointsCoverageAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-559 audit of roster-wide historical Sleeper players_points coverage across weeks 1-18."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperSeasonProviderPointsCoverageAuditCli")
}

val sleeperSeasonProviderPointsDuplicateRosterDiagnostic by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-593 diagnostic of duplicate historical Sleeper roster identity observations."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli")
}

val sleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-594 transaction and final-roster provenance for BF-593 cross-roster duplicates."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli")
}

val sleeperLiveSeasonOperationalReadinessAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-595 audit of 2026 live-season operational readiness."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveSeasonOperationalReadinessAuditCli")
}

val sleeperLiveWaiverUniverseAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-601 proof of the complete active Sleeper identity universe minus exact current roster membership."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverUniverseAuditCli")
}

val sleeperLiveWaiverSnapshotSync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-602 persists an immutable BF-601-proven live waiver identity snapshot and league-eligible projection."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverSnapshotSyncCli")
}

val sleeperLiveWaiverMarketAttentionSync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-603 persists fresh Sleeper add/drop market-attention evidence against the latest roster-stable BF-602 waiver snapshot."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverMarketAttentionSyncCli")
}

val sleeperLiveWaiverProductionCoverageAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-604 audit of exact canonical and governed 2025 production coverage for BF-603 market-active waiver candidates."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverProductionCoverageAuditCli")
}

val sleeperLiveWaiverProductionHydration by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-605 guarded exact canonical bootstrap and target-filtered 2025 nflverse hydration for the latest BF-603 market-active waiver frame."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverProductionHydrationCli")
}

val sleeperLiveWaiverAvailabilitySync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-606 persists immutable current Sleeper availability and depth metadata for the exact latest BF-603 market-active waiver frame."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverAvailabilitySyncCli")
}

val sleeperLiveWaiverCurrentWeekStatSync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-607 persists immutable current-week Sleeper raw-stat evidence for the exact latest BF-603/BF-606 market-active waiver frame."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverCurrentWeekStatSyncCli")
}

val sleeperLiveWaiverPregameEvidenceDossier by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-608 composition of exact BF-603/BF-604/BF-606/BF-607 pregame waiver evidence."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverPregameEvidenceDossierCli")
}

val sleeperCurrentSeasonHydrationEligibilityAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-599 audit of whether the linked 2026 Sleeper league is safe to bootstrap into Butler current roster/player state."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli")
}

val sleeperCurrentSeasonRosterBootstrap by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-600 guarded 2026 current-season roster/player bootstrap with backup, rollback, and BF-598 verification."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCurrentSeasonRosterBootstrapCli")
}

val sleeperCurrentSeasonSuccessorDiscovery by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-596 discovery of the unique lineage-backed 2026 Sleeper successor."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCurrentSeasonSuccessorDiscoveryCli")
}

val sleeperCurrentSeasonSuccessorRelink by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-597 governed compare-and-set relink to the unique BF-596-proven 2026 Sleeper successor."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperCurrentSeasonSuccessorRelinkCli")
}

val sleeperSeasonProviderPointsEvidenceSync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-560 fail-closed atomic persistence of roster-wide historical Sleeper players_points evidence."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperSeasonProviderPointsEvidenceSyncCli")
}

val sleeperSeasonProviderPointsCalibration by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-562 calibration of persisted Sleeper players_points against Butler exact nflverse weekly scoring."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperSeasonProviderPointsCalibrationCli")
}

val sleeperProviderPointsCalibrationCorpusAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-565 audit of every persisted Sleeper provider-points league-season for exact BF-562 calibration eligibility."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperProviderPointsCalibrationCorpusAuditCli")
}

val sleeperProviderNativeSeasonScoringAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-566 proof that persisted Sleeper provider points exactly cover every observed roster identity for a league-season."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperProviderNativeSeasonScoringAuditCli")
}

val sleeperProviderNativeLineupSensitivityCorpusAudit by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-587 audit of the complete persisted provider-points frame through governed provider-native lineup-sensitivity evidence."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli")
}

val sleeperProviderNativeLineupSensitivityConfigurationLineage by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Read-only BF-591 source-lineage diagnostic for zero-common-week provider-native lineup-sensitivity entries."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli")
}

val sleeperHistoricalEffectiveLineupConfigurationSync by tasks.registering(JavaExec::class) {
    group = "application"
    description = "BF-592 governed fixed-frame persistence of effective historical lineup configuration when BF-590 and BF-591 agree exactly."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.butler.bet.cli.ButlerSleeperHistoricalEffectiveLineupConfigurationSyncCli")
}
