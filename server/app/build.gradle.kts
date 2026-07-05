plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("io.javalin:javalin:7.2.2")

    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.5.37")

    implementation("tools.jackson.core:jackson-databind:3.2.0")
    implementation("tools.jackson.dataformat:jackson-dataformat-toml:3.2.0")

    implementation("commons-cli:commons-cli:1.11.0")

    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.w4nya.hamreporter.server.Main"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
