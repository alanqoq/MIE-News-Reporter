import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.mieai.qqbot.plugin"
version = providers.gradleProperty("pluginVersion").orElse("0.1.1").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val qqbotSdkVersion = providers.gradleProperty("qqbotSdkVersion").orElse("1.0.6")
val qqbotSdkRepository = providers.gradleProperty("qqbotSdkRepository")
    .orElse(providers.environmentVariable("QQBOT_SDK_REPOSITORY"))
    .orElse("../../miebot/build/plugin-sdk/repository")

repositories {
    maven { url = uri(qqbotSdkRepository.get()) }
    mavenCentral()
}

val embeddedLibraries by configurations.creating

configurations.compileOnly {
    extendsFrom(embeddedLibraries)
}

configurations.testRuntimeOnly {
    extendsFrom(embeddedLibraries)
}

dependencies {
    compileOnly("com.mieai.qqbot:qqbot-plugin-api:${qqbotSdkVersion.get()}")
    compileOnly("com.mieai.qqbot:qqbot-plugin-spi:${qqbotSdkVersion.get()}")

    embeddedLibraries("com.google.code.gson:gson:2.13.1")
    embeddedLibraries("org.yaml:snakeyaml:2.2")

    testImplementation(kotlin("test-junit5"))
    testImplementation("com.mieai.qqbot:qqbot-plugin-testkit:${qqbotSdkVersion.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xjvm-default=all"))
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("onlineSmokeTest") {
    group = "verification"
    description = "Fetches live news and anime data and writes today's PNG examples."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.mieai.qqbot.plugin.mienr.content.OnlineImageSmoke")
    args(layout.projectDirectory.dir("generated-examples").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

tasks.jar {
    archiveBaseName.set("mie-news-reporter")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ embeddedLibraries.map(::zipTree) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "module-info.class",
            "META-INF/versions/*/module-info.class",
        )
    }
    manifest {
        attributes(
            "Plugin-Id" to "mienr",
            "Plugin-Name" to "MIE News Reporter",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "3.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Default-Config" to "config.yml",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send,media.send,scheduler",
        )
    }
}
