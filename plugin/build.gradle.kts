plugins {
    id("com.gradleup.shadow")
}

val paperApiVersion: String by project
val paperApiDependency = "io.papermc.paper:paper-api:$paperApiVersion"

dependencies {
    implementation(project(":api"))
    implementation(project(":adapters-paper"))
    implementation(project(":adapters-sqlite"))
    compileOnly(paperApiDependency)
    testImplementation(paperApiDependency)
}

tasks {
    jar {
        // Keep the plain module artifact separate from the deployable shaded plugin.
        // Otherwise check/architecture tasks can overwrite the runtime API JAR.
        archiveClassifier.set("plain")
    }
    processResources {
        val expansion = mapOf("version" to project.version.toString())
        inputs.properties(expansion)
        expand(expansion)
    }
    shadowJar {
        archiveClassifier.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
