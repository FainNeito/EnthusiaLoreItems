val paperApiVersion: String by project
val paperApiDependency = "io.papermc.paper:paper-api:$paperApiVersion"

dependencies {
    compileOnly(project(":api"))
    compileOnly(paperApiDependency)
    testImplementation(paperApiDependency)
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
}
