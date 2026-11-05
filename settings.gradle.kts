pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "ONE AI"
include(":app")
include(":one-ai-plugin-sdk")
include(":samples:echo-plugin")
include(":reference-plugins:app-control")
