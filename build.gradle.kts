plugins {
    base
}

allprojects {
    group = "io.butler"
    version = "0.1.0-SNAPSHOT"

    configurations.configureEach {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}
