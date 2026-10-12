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

val rawResourcePack = tasks.register<Zip>("rawResourcePack") {
    from("Glowing Player Outline RP")
    archiveFileName.set("GeyserJavaGlowing.mcpack")
    destinationDirectory.set(layout.buildDirectory.dir("generated/raw-resource-pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val compactPack = layout.buildDirectory.file("generated/resource-pack/GeyserJavaGlowing.mcpack")
val resourcePack = tasks.register<Exec>("resourcePack") {
    dependsOn(rawResourcePack)
    inputs.file(rawResourcePack.flatMap { it.archiveFile })
    inputs.file("scripts/compact_resource_pack.py")
    outputs.file(compactPack)
    commandLine("python3", "scripts/compact_resource_pack.py",
        rawResourcePack.get().archiveFile.get().asFile.absolutePath,
        compactPack.get().asFile.absolutePath)
}

tasks.processResources {
    dependsOn(resourcePack)
    from(compactPack)
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
