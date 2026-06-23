plugins {
    kotlin("jvm") version "2.2.21"
}

group = "io.github.mccitiesclone"
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")

    implementation("net.dv8tion:JDA:5.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            configurations.runtimeClasspath.get().map { dependency ->
                if (dependency.isDirectory) dependency else zipTree(dependency)
            }
        })
    }

    build {
        dependsOn(jar)
    }
}
