val paperApiVersion: String by project
val paperApiDependency = "io.papermc.paper:paper-api:$paperApiVersion"

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":api"))
    implementation("net.kyori:adventure-nbt:4.26.1")
    compileOnly(paperApiDependency)
    testImplementation(paperApiDependency)
    testImplementation(project(":adapters-sqlite"))
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
}
