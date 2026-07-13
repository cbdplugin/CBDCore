plugins {
    id("java")
}

group = "com.cbd"
version = "1.5.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    // 디스코드 Gateway(WebSocket) 메시지 JSON 파싱용. 컴파일 시에는 compileOnly로 참조하고,
    // 런타임에는 plugin.yml의 libraries 선언을 통해 서버가 이 버전을 직접 내려받아 제공한다.
    // (Paper 번들 Gson 버전 변화에 영향받지 않도록 명시적으로 런타임 라이브러리로 선언)
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
