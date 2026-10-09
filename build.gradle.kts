plugins {
    java
}

group = "org.pexserver"
version = providers.gradleProperty("extensionVersion").get()
val resourceVersion = version.toString()

val geyserVersion = providers.gradleProperty("geyserVersion").get()

repositories {
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

dependencies {
    // Direct access to Geyser internals: these are supplied by the running Geyser.
    compileOnly("org.geysermc.geyser:api:$geyserVersion")
    compileOnly("org.geysermc.geyser:core:$geyserVersion")
    testImplementation("org.geysermc.geyser:core:$geyserVersion")
    testImplementation("org.geysermc.geyser:api:$geyserVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val resourcePack = tasks.register<Zip>("resourcePack") {
    from("Glowing Player Outline RP")
    archiveFileName.set("GeyserJavaGlowing.mcpack")
    destinationDirectory.set(layout.buildDirectory.dir("generated/resource-pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(resourcePack)
    filesMatching("extension.yml") {
        expand("version" to resourceVersion)
    }
}

tasks.jar {
    archiveBaseName.set("GeyserJavaGlowing")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.register<Exec>("testResourcePack") {
    dependsOn(resourcePack)
    commandLine("python3", "-m", "unittest", "discover", "-s", "tests", "-v")
}
