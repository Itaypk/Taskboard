import org.gradle.language.jvm.tasks.ProcessResources

plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.spring") version "2.4.10"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.4.10"
}

group = "dev.itayp"
version = "0.0.1-SNAPSHOT"

// Short git commit, baked into build-info.properties so the running app can report which build it is
// (the sync endpoint surfaces it; the SPA prompts a refresh when it changes). Best-effort: falls back
// to "unknown" when git isn't available (e.g. a source-only build environment).
val gitCommit: String = runCatching {
	val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
		.directory(rootDir)
		.redirectErrorStream(true)
		.start()
	val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
	if (process.waitFor() == 0 && output.isNotEmpty()) output else "unknown"
}.getOrDefault("unknown")

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-h2console")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	// JWT/JWKS validation for the Telegram OIDC login flow (NimbusJwtDecoder).
	implementation("org.springframework.security:spring-security-oauth2-jose")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.session:spring-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-liquibase")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.liquibase:liquibase-core")
	implementation("io.micrometer:micrometer-registry-prometheus:1.17.0")
	implementation("net.logstash.logback:logstash-logback-encoder:9.0")
	implementation("com.github.jknack:handlebars:4.5.3")
	implementation("org.telegram:telegrambots-springboot-longpolling-starter:10.0.0")
	implementation("org.telegram:telegrambots-client:10.0.0")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	// Required for the Telegram library
	compileOnly("org.projectlombok:lombok:1.18.46")
	//annotationProcessor("org.projectlombok:lombok:1.18.46")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-restclient")
	testImplementation("org.springframework.boot:spring-boot-resttestclient")
	testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
	testImplementation("org.testcontainers:testcontainers:2.0.5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
}

dependencyManagement {
	imports {}
}

springBoot {
	// Generates META-INF/build-info.properties; SyncController reads the commit to report the version.
	buildInfo {
		properties {
			additional.put("commit", gitCommit)
		}
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.uuid.ExperimentalUuidApi")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()

	// Speed up Spring context startup in tests; safe because tests only exercise the slice they need.
	systemProperty("spring.main.lazy-initialization", "true")

	testLogging {
		events("started", "passed", "failed", "standard_out", "standard_error")
	}
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	// Disables the C2 JIT compiler for faster startup (though worse performance)
	jvmArgs("-XX:TieredStopAtLevel=1")
	args("--spring.profiles.active=dev")
}

val frontendDir = layout.projectDirectory.dir("tasker-frontend")
val frontendDist = frontendDir.dir("dist")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmCmd = if (isWindows) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
	description = "Runs the NPM install step"
    workingDir = frontendDir.asFile
	commandLine(npmCmd, "ci")
	inputs.file(frontendDir.file("package.json"))
	inputs.file(frontendDir.file("package-lock.json"))
	outputs.file(frontendDir.file("node_modules/.package-lock.json"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
	description = "Builds the frontend"
    dependsOn(npmInstall)
	workingDir = frontendDir.asFile
	commandLine(npmCmd, "run", "build")
	inputs.dir(frontendDir.dir("src"))
	inputs.dir(frontendDir.dir("public"))
	inputs.file(frontendDir.file("index.html"))
	inputs.file(frontendDir.file("vite.config.ts"))
	inputs.file(frontendDir.file("tsconfig.json"))
	inputs.file(frontendDir.file("tsconfig.app.json"))
	inputs.file(frontendDir.file("tsconfig.node.json"))
	inputs.file(frontendDir.file("package.json"))
	inputs.file(frontendDir.file("package-lock.json"))
	outputs.dir(frontendDist)
}

tasks.named<ProcessResources>("processResources") {
	mustRunAfter(buildFrontend)
	from(frontendDist) { into("static") }
}

// Full production build: npm install → frontend bundle → backend JAR with frontend embedded.
// Default `build` / `bootRun` skip npm entirely.
tasks.named("build") { mustRunAfter(buildFrontend) }

tasks.register("release") {
	group = "build"
	description = "Builds the full application with the frontend bundle embedded in the JAR"
	dependsOn(buildFrontend, "build")
}
