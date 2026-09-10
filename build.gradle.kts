import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.util.UUID

plugins {
    base
}

allprojects {
    group = "io.butler"
    version = "0.1.0-SNAPSHOT"

    configurations.configureEach {
        withDependencies {
            filterIsInstance<ExternalModuleDependency>().forEach { dependency ->
                if (dependency.isChanging) {
                    throw GradleException(
                        "Changing dependency modules are not allowed: " +
                            "${dependency.group}:${dependency.name}:${dependency.version}"
                    )
                }
            }
        }
        resolutionStrategy.eachDependency {
            val requestedVersion = requested.version
            val isDynamic = requestedVersion != null && (
                requestedVersion.contains("+") ||
                    requestedVersion.startsWith("latest.") ||
                    requestedVersion.startsWith("[") ||
                    requestedVersion.startsWith("(")
                )
            if (isDynamic) {
                throw GradleException(
                    "Dynamic dependency versions are not allowed: " +
                        "${requested.group}:${requested.name}:$requestedVersion"
                )
            }
        }
    }
}

val betCliProject = project(":bet:bet-cli")
betCliProject.plugins.withId("java") {
    betCliProject.tasks.withType<Test>()
        .matching { it.name == "butlerAcceptanceTest" }
        .configureEach {
            val invocationResultsDir = File(
                System.getProperty("java.io.tmpdir"),
                "butler-gradle/butlerAcceptanceTest/${UUID.randomUUID()}"
            )
            binaryResultsDirectory.set(File(invocationResultsDir, "binary"))
            reports.junitXml.outputLocation.set(File(invocationResultsDir, "junit-xml"))
            reports.html.outputLocation.set(File(invocationResultsDir, "html"))
        }
}

tasks.register("butlerAcceptanceTest") {
    group = "verification"
    description = "Runs the Butler acceptance test suite."
    dependsOn(":bet:bet-cli:butlerAcceptanceTest")
}
