// Platform-free utilities usable on Paper and Velocity. Plain Java with no runtime dependencies,
// so proxy plugins can bundle it without the Kotlin stdlib. The Paper plugin bundles and re-exports it.
plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnlyApi("org.jspecify:jspecify:1.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("sneakyutils-common")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks {
    test {
        useJUnitPlatform()
        // Real-environment fixture for ServerUtilEnvironmentTest.
        environment("P_SERVER_NAME", "Dev Main")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "sneakyutils-common"
            from(components["java"])
            // Library, not a deployable plugin: no sneakyrp.deployment.type.
            pom {
                name.set("SneakyUtils Common")
                description.set(
                    "Platform-free SneakyUtils helpers for Team-Sneakymouse Paper and Velocity plugins."
                )
                url.set("https://github.com/Team-Sneakymouse/SneakyUtils")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
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
