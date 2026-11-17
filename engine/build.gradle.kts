plugins {
    id("org.jetbrains.kotlin.jvm")
}

// تست‌ها بدون وابستگی خارجی (کتابخانه‌ی تست لازم نیست)
tasks.register<JavaExec>("engineTests") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.indsim.engine.EngineTestsKt")
}
