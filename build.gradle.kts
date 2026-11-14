import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.3.21"
    `java-library`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    `maven-publish`
}

// Requires a git checkout with at least one commit (CI and local clones).
val gitVersion =
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
    }.standardOutput.asText.map { commit ->
        val (timestamp, hash) = commit.trim().split(":", limit = 2)
        val date =
            DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(timestamp.toLong()))
        "$date-$hash"
    }.get()

// Shared by the Paper plugin and :common so the published versions always match.
allprojects {
    group = "io.github.team-sneakymouse"
    version = gitVersion
}

repositories {
    mavenCentral()
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApi = "26.2.build.+"
val mockBukkitVersion = "4.117.0"

dependencies {
    // api: compileOnly consumers of sneakyutils still see ServerUtil. Bundled into the plugin jar below.
    api(project(":common"))
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("me.clip:placeholderapi:2.11.5")
    // PlayerConcealment's packet handler; the server provides Netty.
    compileOnly("io.netty:netty-transport:4.2.15.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")

    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:$mockBukkitVersion")
    testImplementation("me.clip:placeholderapi:2.11.5")
    testImplementation("io.netty:netty-transport:4.2.15.Final")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks {
    processResources {
        inputs.property("version", project.version.toString())
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version.toString())
        }
    }

    jar {
        archiveBaseName.set(rootProject.name)
        archiveFileName.set("${rootProject.name}-${project.version}.jar")
        // Lazy, with dependsOn, so :common:jar is built before it is bundled.
        dependsOn(configurations.runtimeClasspath)
        from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    runServer {
        minecraftVersion("26.2")
    }

    test {
        useJUnitPlatform()
    }

    val publishPreflight by registering(Exec::class) {
        group = "verification"
        description = "Catch sneakyrp publish footguns (gradlew mode, wrapper jar, workflow_dispatch)."
        workingDir = rootDir
        commandLine("bash", "scripts/publish-preflight.sh", rootDir.absolutePath)
    }

    check {
        dependsOn(publishPreflight)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "sneakyutils"
            from(components["java"])
            pom {
                name.set("SneakyUtils")
                description.set(
                    "Shared Paper utilities and managers for Team-Sneakymouse / Lords of Minecraft plugins."
                )
                url.set("https://github.com/Team-Sneakymouse/SneakyUtils")
                properties.put("sneakyrp.deployment.type", "paper-plugin")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("team-sneakymouse")
                        name.set("Team Sneakymouse")
                        url.set("https://github.com/Team-Sneakymouse")
                    }
                }
                scm {
                    url.set("https://github.com/Team-Sneakymouse/SneakyUtils")
                    connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyUtils.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sneakyrp"
            url = uri("https://maven.sneakyrp.com/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<org.gradle.authentication.http.BasicAuthentication>("basic")
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.check)
}
