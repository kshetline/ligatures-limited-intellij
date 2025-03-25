plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.7.22"
  id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.shetline"
version = "1.0.8"

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
intellij {
  version.set("2023.1.5")
  type.set("IC") // Target IDE Platform
  updateSinceUntilBuild.set(false)
}

repositories {
  mavenCentral()
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "11"
    targetCompatibility = "11"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
  }

  patchPluginXml {
    sinceBuild.set("212.5712.43")
  }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))
  }
}
