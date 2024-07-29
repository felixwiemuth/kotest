package io.kotest.framework.multiplatform.gradle

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.core.test.TestScope
import io.kotest.inspectors.forAll
import io.kotest.inspectors.forAtLeastOne
import io.kotest.inspectors.shouldForOne
import io.kotest.matchers.paths.shouldBeAFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
import kotlin.io.path.CopyActionResult
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.useLines
import kotlin.io.path.writeText

class KotestMultiplatformCompilerGradlePluginSpec : ShouldSpec({
   setOf(
      "1.9.24",
      "2.0.0",
   ).forEach { kotlinVersion ->
      context("when the project targets Kotlin version $kotlinVersion") {

         should("be able to load project") {
            runGradle(
               kotlinVersion = kotlinVersion,
               taskNames = listOf("help"),
            ) { result ->
               result.result.tasks.shouldForOne {
                  it.path shouldBe ":help"
                  it.outcome shouldBe TaskOutcome.SUCCESS
               }
            }
         }

         fun GradleInvocation.Result.shouldHaveExpectedTestResultsFor(taskName: String) {
            withClue("$taskName test report") {
               val testReportFile = testReportsDirectory.resolve("$taskName/TEST-TestSpec.xml")
               testReportFile.shouldBeAFile()

               val testReportContentBeginning = testReportFile.useLines { it.take(2).joinToString("\n") }

               testReportContentBeginning.shouldStartWith(
                  """
                  <?xml version="1.0" encoding="UTF-8"?>
                  <testsuite name="TestSpec" tests="3" skipped="0" failures="1" errors="0"
                  """.trimIndent()
               )
            }
         }

         should("be able to compile and run tests for the JVM, JS and Wasm/JS targets") {
            val taskNames = listOf(
               "jvmTest",
               "jsBrowserTest",
               "jsNodeTest",
               "wasmJsBrowserTest",
               "wasmJsNodeTest",
            )

            runGradle(
               kotlinVersion = kotlinVersion,
               taskNames = taskNames,
            ) { result ->
               taskNames.forAll { taskName ->
                  result.shouldHaveExpectedTestResultsFor(taskName)
               }
            }
         }

         should("be able to compile and run tests for all native targets supported by the host machine") {
            val taskNames = listOf(
               "macosArm64Test",
               "macosX64Test",
               "mingwX64Test",
               "linuxX64Test",
            )

            runGradle(
               kotlinVersion = kotlinVersion,
               taskNames = taskNames,
            ) { result ->
               taskNames.forAtLeastOne { taskName ->
                  // Depending on the host machine these tests are running on,
                  // only one of the test targets will be built and executed.
                  result.shouldHaveExpectedTestResultsFor(taskName)
               }
            }
         }
      }
   }
})

private fun TestScope.runGradle(
   kotlinVersion: String,
   taskNames: List<String>,
   block: (result: GradleInvocation.Result) -> Unit,
) {
   GradleInvocation(
      kotlinVersion = kotlinVersion,
      taskNames = taskNames,
      testId = testCase.descriptor.id.value,
   ).use { gradle ->
      val result = gradle.run()
      println("[${testCase.name.testName}] result log ${result.output.absolute()}")
      withClue({ result.clue() }) {
         block(result)
      }
   }
}

private data class GradleInvocation(
   val kotlinVersion: String,
   val taskNames: List<String>,
   val testId: String,
) : AutoCloseable {
   val projectDir = createTempDirectory("kotest-gradle-plugin-test")

   data class Result(
//      val command: List<String>,
      val output: Path,
//      val exitCode: Int,
      val projectDir: Path,
      val result: BuildResult,
   ) {
      val testReportsDirectory: Path = projectDir.resolve("build/test-results")

      fun clue(): String =
         output.readText().prependIndent("\t>>> ")
//         "Gradle process $command exited with code $exitCode and output:\n" + output.readText().prependIndent("\t>>> ")
   }

   fun run(): Result {
      prepareProjectDir(projectDir)

      val logFile = createTempFile(testLogDir, testId.replaceNonAlphanumeric(), ".log")

      val result = logFile.bufferedWriter().use { logWriter ->
         @Suppress("UnstableApiUsage")
         GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .forwardStdOutput(logWriter)
//            .apply {
//               withEnvironment(
//                  buildMap {
//                     putAll(environment.orEmpty())
//                     System.getenv("KONAN_DATA_DIR")?.let { konanDataDir ->
//                        put("KONAN_DATA_DIR", konanDataDir)
//                     }
//                     put("PATH", System.getenv("PATH"))
//                     //put("GRADLE_USER_HOME", gradleUserHome.toString())
//                     hostGradleUserHome.resolve("caches")
//                        .takeIf { it.exists() }
//                        ?.let { guh ->
//                           put("GRADLE_RO_DEP_CACHE", guh.invariantSeparatorsPathString)
//                        }
//                  }
//               )
//            }
            .withArguments(
               buildList {
                  add("--continue")
                  add("--stacktrace")
                  //add("--info")
                  addAll(taskNames)
               }
            )
            .run()
      }

//      val command = buildList {
//         add(wrapperScriptPath.toString())
//      }

//      val process = ProcessBuilder(command)
//         .directory(projectDir.toFile())
//         .redirectOutput(logFile.toFile())
//         .redirectError(logFile.toFile())
//         .redirectErrorStream(true)
//         .apply {
//            environment().apply {
//            }
//         }
//         .start()

      return Result(
//         command = command,
         output = logFile,
//         exitCode = process.waitFor(),
         projectDir = projectDir,
         result = result,
      )
   }

   private fun prepareProjectDir(projectDir: Path): Path {
      val excludedDirs = setOf(
         ".kotlin",
         "build",
         ".gradle",
         ".idea",
         "kotlin-js-store",
      )

      testProjectDir.copyToRecursively(
         target = projectDir,
         followLinks = false,
      ) { src, target ->
         if (src.isDirectory() && src.name in excludedDirs) {
            CopyActionResult.SKIP_SUBTREE
         } else {
            src.copyToIgnoringExistingDirectory(target, followLinks = false)
         }
      }

      projectDir.resolve("gradle.properties").apply {
         writeText(
            buildString {
               appendLine(readText())
               appendLine("kotlinVersion=$kotlinVersion")
               appendLine("kotestVersion=$kotestVersion")
               appendLine("devMavenRepoPath=$devMavenRepoPath")
            }
         )
      }

      return projectDir
   }

   override fun close() {
//      projectDir.deleteRecursively()
   }

   companion object {

      /** Access the current host's Gradle user dir, to use as a read-only cache. */
      private val hostGradleUserHome = Path(System.getProperty("gradleUserHomeDir"))

      private val testLogDir = Path(System.getProperty("testLogDir"))
         .resolve(LocalDateTime.now().format(ISO_LOCAL_DATE_TIME).replaceNonAlphanumeric())
         .createDirectories()

//      /** Use a stable Gradle user home for each test. */
//      private val gradleUserHome = Files.createTempDirectory("test-gradle-user-home")

      private val kotestVersion = System.getProperty("kotestVersion")
      private val devMavenRepoPath = System.getProperty("devMavenRepoPath")

      /** The source project that will be tested. This directory should not be modified. */
      private val testProjectDir = Path(System.getProperty("testProjectDir"))

      private fun String.replaceNonAlphanumeric(
         replacement: String = "-"
      ): String =
         map { if (it.isLetterOrDigit()) it else replacement }.joinToString("")
   }
}
