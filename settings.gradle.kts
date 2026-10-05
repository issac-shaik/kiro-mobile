pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "KiroMobile"
include(":app", ":core:design", ":core:protocol", ":core:connection", ":feature:notifications", ":feature:chat")
