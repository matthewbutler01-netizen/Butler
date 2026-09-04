plugins {
    base
}

allprojects {
    group = "io.butler"
    version = "0.1.0-SNAPSHOT"

    configurations.configureEach {
        resolutionStrategy {
            eachDependency {
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
            failOnChangingVersions()
        }
    }
}
