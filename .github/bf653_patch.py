from pathlib import Path

capture_path = Path('bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverGovernedExplanationCapture.java')
text = capture_path.read_text(encoding='utf-8')
old = '''            || target.rosterId() != audit.rosterId()\n            || target.providerSeason() != audit.season()\n            || !target.providerStatus().equals(audit.providerStatus())\n            || !Objects.equals(target.providerLeg(), audit.providerLeg())) {'''
new = '''            || target.rosterId() != audit.rosterId()\n            || audit.season() != SleeperPersonalizedTargetService.TARGET_SEASON\n            || !target.providerStatus().equals(audit.providerStatus())) {'''
if old not in text:
    raise SystemExit('BF-653 target reconciliation patch marker missing')
text = text.replace(old, new, 1)
capture_path.write_text(text, encoding='utf-8')

build_path = Path('bet/bet-cli/build.gradle.kts')
build = build_path.read_text(encoding='utf-8')
marker = '''val sleeperLiveWaiverRecommendationAuditCapture by tasks.registering(JavaExec::class) {\n    group = "application"\n    description = "BF-627 explicitly persists an immutable audit record of the BF-623-verified governed live waiver outcome without executing Sleeper transactions."\n    classpath = sourceSets["main"].runtimeClasspath\n    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverRecommendationAuditCaptureCli")\n}\n'''
addition = marker + '''\nval sleeperLiveWaiverGovernedExplanationCapture by tasks.registering(JavaExec::class) {\n    group = "application"\n    description = "BF-653 explicitly persists an immutable explanation companion for one existing BF-627 governed waiver audit after exact reconciliation."\n    classpath = sourceSets["main"].runtimeClasspath\n    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverGovernedExplanationCaptureCli")\n}\n\nval sleeperLiveWaiverGovernedExplanationLookup by tasks.registering(JavaExec::class) {\n    group = "application"\n    description = "Read-only BF-653 lookup of the persisted governed explanation companion for one exact BF-627 audit id."\n    classpath = sourceSets["main"].runtimeClasspath\n    mainClass.set("io.butler.bet.cli.ButlerSleeperLiveWaiverGovernedExplanationLookupCli")\n}\n'''
if marker not in build:
    raise SystemExit('BF-653 Gradle insertion marker missing')
if 'sleeperLiveWaiverGovernedExplanationCapture by tasks.registering' in build:
    raise SystemExit('BF-653 Gradle tasks already present')
build = build.replace(marker, addition, 1)
build_path.write_text(build, encoding='utf-8')
