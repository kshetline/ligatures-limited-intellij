plugins {
  id("org.jetbrains.intellij") version "0.4.20"
  java
  kotlin("jvm") version "1.3.72"
}

group = "com.shetline"
version = "1.0.5"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation(kotlin("reflect"))
  implementation("com.google.code.gson:gson:2.8.9")
  // testCompile("junit", "junit", "4.12")
}

 intellij {
  version = "IC-2020.3.2"
  updateSinceUntilBuild = false
 }

configure<JavaPluginConvention> {
  sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks {
  compileKotlin {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
  }

  compileTestKotlin {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
  }
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.getByName<org.jetbrains.intellij.tasks.PatchPluginXmlTask>("patchPluginXml") {
  changeNotes("""
      <h2>1.0.5</h2>
      <ul><li>Fix a null exception.</ul>
      <h2>1.0.4</h2>
      <ul><li>Fix occasional exceptions thrown by disposed editors.</ul>
      <h2>1.0.3</h2>
      <ul><li>Fix handling of cursor position when tab characters are used for indentation.</ul>
      <h2>1.0.2</h2>
      <ul><li>Fix bug where extension would fail to start when currently available languages could not be checked.</ul>
      <h2>1.0.1</h2>
      <ul><li>Recognize as a ligature four or more dashes, by themselves, without a leading <b>&lt;</b> or trailing <b>&gt;</b>.</ul>
      <h2>1.0.0</h2>
      <ul><li>First stable release</li></ul>
"""
  )
}
