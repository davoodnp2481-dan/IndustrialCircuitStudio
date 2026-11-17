plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":engine"))
}

tasks.register<JavaExec>("editorTests") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.indsim.editor.EditorTestsKt")
}
