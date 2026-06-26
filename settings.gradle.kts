pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}
rootProject.name = "CarNavRelayBT"
include(":app")
include(":dashcam-feature")
include(":gocam")
project(":gocam").projectDir = file("GO cam")
