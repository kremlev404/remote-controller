plugins {
    alias(libs.plugins.remotecontroller.jvm.server)
    application
}

application {
    mainClass.set("com.remotecontroller.relay.MainKt")
}
