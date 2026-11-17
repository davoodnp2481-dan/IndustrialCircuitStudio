plugins {
    id("org.jetbrains.kotlin.jvm")
}

tasks.register<JavaExec>("canvasTests") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.indsim.canvas.CanvasTestsKt")
}
