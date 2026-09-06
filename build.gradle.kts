import org.gradle.api.artifacts.ExternalModuleDependency

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

tasks.register("butlerAcceptanceTest") {
    group = "verification"
    description = "Runs the Butler acceptance test suite."
    dependsOn(":bet:bet-cli:butlerAcceptanceTest")
}
