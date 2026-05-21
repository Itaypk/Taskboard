import org.gradle.language.jvm.tasks.ProcessResources

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "dev.itayp"
version = "0.0.1-SNAPSHOT"

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
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.session:spring-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-liquibase")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.liquibase:liquibase-core")
	implementation("io.micrometer:micrometer-registry-prometheus:1.16.5")
	implementation("net.logstash.logback:logstash-logback-encoder:9.0")
	implementation("com.github.jknack:handlebars:4.5.1")
	implementation("org.telegram:telegrambots-springboot-longpolling-starter:9.6.0")
	implementation("org.telegram:telegrambots-client:9.6.0")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
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

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()

	testLogging {
		events("started", "passed", "failed", "standard_out", "standard_error")
	}
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	args("--spring.profiles.active=dev")
}

val frontendDir = layout.projectDirectory.dir("tasker-frontend")
val frontendDist = frontendDir.dir("dist")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmCmd = if (isWindows) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
	workingDir = frontendDir.asFile
	commandLine(npmCmd, "ci")
	inputs.file(frontendDir.file("package.json"))
	inputs.file(frontendDir.file("package-lock.json"))
	outputs.file(frontendDir.file("node_modules/.package-lock.json"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
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
