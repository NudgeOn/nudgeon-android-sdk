// 독립 빌드다. 루트 멀티프로젝트에 포함되지 않는다 — SDK를 로컬 모듈이 아니라
// Maven Central에서 가져오는지 실제로 검증하기 위해서다.
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
        google()          // AndroidX
        mavenCentral()    // io.nudgeon:nudgeon-sdk
    }
}
rootProject.name = "nudgeon-quickstart"
