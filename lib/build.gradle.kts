plugins {
    alias(libs.plugins.jvm)
    `java-library`
    `maven-publish`
}

version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.ktor.client)
    implementation(libs.logback.classic)
    implementation(libs.jackson.kotlin)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.home-assistant-api"
            artifactId = "home-assistant-api"
            version = project.version.toString()

            from(components["java"])
        }
    }
}
