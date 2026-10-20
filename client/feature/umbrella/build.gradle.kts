plugins {
    alias(libs.plugins.remotecontroller.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":client:feature:connect:impl"))
            api(project(":client:feature:remote:impl"))
            api(project(":client:feature:settings:impl"))
            api(project(":client:feature:screen:impl"))
            implementation(libs.koin.core)
        }
    }
}
