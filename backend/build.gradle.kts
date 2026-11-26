plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    // Bytecode targets Java 21 regardless of the JDK running Gradle (see docs/DECISIONS.md).
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.add("-parameters")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // MockWebServer plays OpenAI/Anthropic; the official SDK proves base-URL-only compatibility (SPEC.md, Testing).
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1") // same OkHttp line as openai-java
    testImplementation("com.openai:openai-java:4.112.0")
    testImplementation("com.anthropic:anthropic-java:3.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Same as main(): run in UTC so results never depend on the developer's host time zone.
    systemProperty("user.timezone", "UTC")
}

tasks.bootJar {
    archiveFileName = "llm-cost-tracker.jar"
}

tasks.jar {
    enabled = false
}
