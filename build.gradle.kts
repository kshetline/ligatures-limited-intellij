plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.9.21"
  id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.shetline"
version = "1.0.6"

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
  version.set("2023.1.5")
  type.set("IC") // Target IDE Platform

  plugins.set(listOf(/* Plugin Dependencies */))
}

repositories {
  mavenCentral()

//  intellijPlatform {
//    releases()
//    marketplace()
//  }
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
  }

  patchPluginXml {
    sinceBuild.set("231")
    untilBuild.set("241.*")
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

tasks.getByName<org.jetbrains.intellij.tasks.PatchPluginXmlTask>("patchPluginXml") {
//  changeNotes("""
//    <h2>1.0.5</h2>
//    <ul><li>Fix a null exception.</ul>
//    <h2>1.0.4</h2>
//    <ul><li>Fix occasional exceptions thrown by disposed editors.</ul>
//    <h2>1.0.3</h2>
//    <ul><li>Fix handling of cursor position when tab characters are used for indentation.</ul>
//    <h2>1.0.2</h2>
//    <ul><li>Fix bug where extension would fail to start when currently available languages could not be checked.</ul>
//    <h2>1.0.1</h2>
//    <ul><li>Recognize as a ligature four or more dashes, by themselves, without a leading <b>&lt;</b> or trailing <b>&gt;</b>.</ul>
//    <h2>1.0.0</h2>
//    <ul><li>First stable release</li></ul>
//"""
//  )
}
