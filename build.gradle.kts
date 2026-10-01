import java.net.URI
import java.security.MessageDigest

plugins {
    `java-library`
}

group = "com.mikumc"
version = "1.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
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

tasks.named("compileJava") { dependsOn(downloadVelocityProxy) }

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    mavenCentral()
}

dependencies {
    compileOnly(files(velocityJar))
    // 注解处理器生成 velocity-plugin.json，走公开的 velocity-api 构件即可
    annotationProcessor("com.velocitypowered:velocity-api:$velocityVersion")
}

tasks.jar {
    archiveBaseName.set("MikuMOTD")
}
