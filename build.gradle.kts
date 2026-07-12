plugins {
    id("java")
}

group = "com.cbd"
version = "1.4.0"

// 프로젝트 경로에 포함된 한글 때문에 Windows에서 테스트 실행용 JVM 인자 파일(@argfile)의
// 클래스패스 인코딩이 깨져 테스트 클래스를 찾지 못하는 문제가 있어, 빌드 출력 경로를
// ASCII 전용 경로로 옮긴다. (소스 코드 위치는 그대로, 산출물 위치만 변경됨)
layout.buildDirectory.set(file(System.getProperty("user.home") + "/.cbdcore-build"))

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    // 디스코드 Gateway(WebSocket) 메시지 JSON 파싱용. Paper 서버는 Mojang/Vanilla 코드가
    // 내부적으로 Gson을 오래전부터 사용해왔기 때문에 런타임 클래스패스에 항상 존재하며,
    // paper-api와 동일하게 compileOnly로만 선언한다 (직접 번들하지 않음).
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
