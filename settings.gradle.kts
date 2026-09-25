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
        // Stellar（Shizuku 分支，见 docs/third-party/Stellar-API-INTEGRATION_GUIDE.md）只发在 JitPack 上，Maven Central / 阿里云都没有。
        // 收窄到它的两个 group，别让别的依赖绕到 JitPack（它慢）。
        // 本机还得在 $GRADLE_USER_HOME/init.d/aliyun-mirror.gradle 里也加一份：那个脚本会 clear() 掉这里。
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.roro2239")
                includeGroup("com.github.roro2239.Stellar-API")
            }
        }
    }
}

rootProject.name = "Sentinel52"
include(":app")
