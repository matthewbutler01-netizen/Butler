import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "Butler"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include("bet:bet-cli")
