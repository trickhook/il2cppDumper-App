plugins {
    kotlin("jvm") version "2.1.20"
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
