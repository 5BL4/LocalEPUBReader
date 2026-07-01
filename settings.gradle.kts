pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "EpubReader"
include(":app")

// P2 连续滚动: 用本地 fork 的 readium 源码替换 Maven AAR (includeBuild + dependencySubstitution)
// 始终用 fork, "回退" = git revert 连续滚动改动 (fork 无改动时等价于 v3.3.0 AAR)
includeBuild("modules/readium") {
    dependencySubstitution {
        substitute(module("org.readium.kotlin-toolkit:readium-shared")).using(project(":readium:readium-shared"))
        substitute(module("org.readium.kotlin-toolkit:readium-streamer")).using(project(":readium:readium-streamer"))
        substitute(module("org.readium.kotlin-toolkit:readium-navigator")).using(project(":readium:readium-navigator"))
    }
}
