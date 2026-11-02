plugins {
    alias(libs.plugins.remotecontroller.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":client:kmp:feature:connect:impl"))
            api(project(":client:kmp:feature:remote:impl"))
            api(project(":client:kmp:feature:settings:impl"))
            api(project(":client:kmp:feature:screen:impl"))
            implementation(libs.koin.core)
        }
    }
}
