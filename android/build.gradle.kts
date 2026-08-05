allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// Workaround for permission_handler_android: lint tasks fail with ClassNotFoundException
// (LintCliClient, UastEnvironment, etc.) due to AGP/Gradle classpath issue.
// Disable broken lint tasks and create extractReleaseAnnotations output so downstream tasks succeed.
gradle.taskGraph.whenReady {
    allprojects.forEach { proj ->
        proj.tasks.findByName("extractReleaseAnnotations")?.let { extractTask ->
            extractTask.enabled = false
            val typedefFile = proj.layout.buildDirectory.file("intermediates/annotations_typedef_file/release/extractReleaseAnnotations/typedefs.txt").get().asFile
            typedefFile.parentFile.mkdirs()
            if (!typedefFile.exists()) typedefFile.writeText("")
        }
        // Disable other lint tasks that use the same broken classpath
        listOf("lintVitalAnalyzeRelease", "lintVitalReportRelease", "lintAnalyzeRelease").forEach { taskName ->
            proj.tasks.findByName(taskName)?.enabled = false
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
