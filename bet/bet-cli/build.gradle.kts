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
