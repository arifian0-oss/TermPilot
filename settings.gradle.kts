pluginManagement {
    repositories {
        // Local curl-mirrored repo first: the sandbox egress proxy breaks
        // Java HTTP, so all artifacts are pre-fetched (see tools/fetch_deps.py).
        maven { url = uri(rootDir.resolve("local-repo")) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(rootDir.resolve("local-repo")) }
        google()
        mavenCentral()
    }
}
rootProject.name = "TermPilot"
include(":app")
include(":terminal-emulator")
include(":terminal-view")
