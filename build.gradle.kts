plugins {
  id("org.jetbrains.intellij") version "0.4.20"
  java
  kotlin("jvm") version "1.3.72"
}

group = "com.shetline"
version = "0.0.4-alpha"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation(kotlin("reflect"))
  implementation("com.google.code.gson:gson:2.8.6")
  // testCompile("junit", "junit", "4.12")
}

intellij {
  version = "IC-2020.1.2"
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

tasks.getByName<org.jetbrains.intellij.tasks.PatchPluginXmlTask>("patchPluginXml") {
  changeNotes(
    """
      <h2>0.0.4</h2>
      <ul><li>Description update</li></ul>
      <h2>0.0.1</h2>
      <ul><li>First release</li></ul>"""
  )
}
