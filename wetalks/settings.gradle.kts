pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/") }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/") }
}
rootProject.name = "WeTalks"
include(":app")
