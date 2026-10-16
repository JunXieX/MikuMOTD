import java.net.URI
import java.security.MessageDigest

plugins {
    `java-library`
}

group = "com.mikumc"
version = "1.6.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// 构建期生成版本常量：@Plugin 注解只能取编译期常量，源码里不再重复写版本号
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildinfo/java")
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val packageDir = outputDir.get().asFile.resolve("com/mikumc/motd")
        packageDir.mkdirs()
        packageDir.resolve("BuildInfo.java").writeText(
            """
            |package com.mikumc.motd;
            |
            |/** 构建期生成：版本号的唯一来源是 build.gradle.kts 的 version。 */
            |public final class BuildInfo {
            |
            |    public static final String VERSION = "$pluginVersion";
            |
            |    private BuildInfo() {
            |    }
            |}
            |
            """.trimMargin()
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildInfo)
}

// MikuMOTD 的快速路径建立在 Velocity 代理的内部网络实现之上，
// 而内部类不在公开的 velocity-api 构件中，因此编译期直接使用与运行时
// 完全一致的官方代理 fat jar（内容寻址下载，带 SHA-256 校验）。
val velocityVersion = "4.2.0"
val velocityBuild = "30"
val velocityJarName = "velocity-$velocityVersion-$velocityBuild.jar"
val velocitySha256 = "35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8"
val velocityJarSize = 42163652L
val velocityJarUrl =
    "https://fill-data.papermc.io/v1/objects/$velocitySha256/$velocityJarName"
val velocityJar = layout.buildDirectory.file("velocity/$velocityJarName")

val downloadVelocityProxy = tasks.register("downloadVelocityProxy") {
    outputs.file(velocityJar)
    doLast {
        val target = velocityJar.get().asFile
        if (!target.exists() || target.length() != velocityJarSize) {
            target.parentFile.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            URI.create(velocityJarUrl).toURL().openStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != velocitySha256) {
                target.delete()
                throw GradleException("Checksum mismatch for $velocityJarName: $actual")
            }
        }
    }
}

// 所有编译任务（含测试编译）都需要代理 jar 提供的内部类
tasks.withType<JavaCompile>().configureEach { dependsOn(downloadVelocityProxy) }

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    mavenCentral()
}

dependencies {
    compileOnly(files(velocityJar))
    // 注解处理器生成 velocity-plugin.json，走公开的 velocity-api 构件即可
    annotationProcessor("com.velocitypowered:velocity-api:$velocityVersion")
    // 测试同样需要代理 fat jar 提供的运行时类（netty/adventure/configurate）
    testImplementation(files(velocityJar))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    archiveBaseName.set("MikuMOTD")
}
