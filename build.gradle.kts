import org.gradle.language.jvm.tasks.ProcessResources

plugins {
	kotlin("jvm") version "2.3.20"
	kotlin("plugin.spring") version "2.3.20"
	id("org.springframework.boot") version "4.0.5"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.20"
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
	implementation("org.springframework.boot:spring-boot-starter-liquibase")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.liquibase:liquibase-core")
	implementation("io.micrometer:micrometer-registry-prometheus:1.16.5")
	implementation("net.logstash.logback:logstash-logback-encoder:9.0")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-restclient")
	testImplementation("org.springframework.boot:spring-boot-resttestclient")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
	dependsOn(buildFrontend)
	from(frontendDist) { into("static") }
}
