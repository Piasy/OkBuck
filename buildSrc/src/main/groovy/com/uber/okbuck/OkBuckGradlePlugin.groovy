package com.uber.okbuck

import com.uber.okbuck.config.BUCKFile
import com.uber.okbuck.core.dependency.DependencyCache
import com.uber.okbuck.core.task.OkBuckCleanTask
import com.uber.okbuck.core.util.LintUtil
import com.uber.okbuck.core.util.RetrolambdaUtil
import com.uber.okbuck.core.util.RobolectricUtil
import com.uber.okbuck.core.util.TransformUtil
import com.uber.okbuck.extension.ExperimentalExtension
import com.uber.okbuck.extension.IntellijExtension
import com.uber.okbuck.extension.LintExtension
import com.uber.okbuck.extension.OkBuckExtension
import com.uber.okbuck.extension.RetrolambdaExtension
import com.uber.okbuck.extension.TestExtension
import com.uber.okbuck.extension.TransformExtension
import com.uber.okbuck.extension.WrapperExtension
import com.uber.okbuck.generator.BuckFileGenerator
import com.uber.okbuck.generator.DotBuckConfigLocalGenerator
import com.uber.okbuck.wrapper.BuckWrapperTask
import org.apache.commons.io.IOUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logger

class OkBuckGradlePlugin implements Plugin<Project> {

    static final String OKBUCK = "okbuck"
    static final String OKBUCK_CLEAN = 'okbuckClean'
    static final String BUCK = "BUCK"
    static final String EXPERIMENTAL = "experimental"
    static final String INTELLIJ = "intellij"
    static final String TEST = "test"
    static final String GRADLE_GEN = "gradleGen"
    static final String WRAPPER = "wrapper"
    static final String BUCK_WRAPPER = "buckWrapper"
    static final String DEFAULT_CACHE_PATH = ".okbuck/cache"
    static final String GROUP = "okbuck"
    static final String BUCK_LINT = "buckLint"
    static final String LINT = "lint"
    static final String TRANSFORM = "transform"
    static final String RETROLAMBDA = "retrolambda"

    static DependencyCache depCache
    static Logger LOGGER

    void apply(Project project) {
        LOGGER = project.logger
        OkBuckExtension okbuck = project.extensions.create(OKBUCK, OkBuckExtension, project)
        WrapperExtension wrapper = okbuck.extensions.create(WRAPPER, WrapperExtension)
        ExperimentalExtension experimental = okbuck.extensions.create(EXPERIMENTAL, ExperimentalExtension)
        TestExtension test = okbuck.extensions.create(TEST, TestExtension)
        IntellijExtension intellij = okbuck.extensions.create(INTELLIJ, IntellijExtension)
        LintExtension lint = okbuck.extensions.create(LINT, LintExtension, project)
        TransformExtension transform = okbuck.extensions.create(TRANSFORM, TransformExtension)
        RetrolambdaExtension retrolambda = okbuck.extensions.create(RETROLAMBDA, RetrolambdaExtension)

        Task okBuck = project.task(OKBUCK)
        okBuck.setGroup(GROUP)
        okBuck.setDescription("Generate BUCK files")
        okBuck.outputs.upToDateWhen { false }

        project.configurations.maybeCreate(TransformUtil.CONFIGURATION_TRANSFORM)

        project.afterEvaluate {
            Task okBuckClean = project.tasks.create(OKBUCK_CLEAN, OkBuckCleanTask, {
                dir = project.projectDir.absolutePath
                includes = wrapper.remove
                excludes = wrapper.keep
            })
            okBuckClean.setGroup(GROUP)
            okBuckClean.setDescription("Delete configuration files generated by OkBuck")

            okBuck.dependsOn(okBuckClean)
            okBuck.doLast {
                generate(project)
            }

            depCache = new DependencyCache(project, DEFAULT_CACHE_PATH, true, DependencyCache.THIRD_PARTY_BUCK_FILE,
                    intellij.sources, experimental.lint)

            if (test.robolectric) {
                Task fetchRobolectricRuntimeDeps = project.task('fetchRobolectricRuntimeDeps')
                okBuck.dependsOn(fetchRobolectricRuntimeDeps)
                fetchRobolectricRuntimeDeps.mustRunAfter(okBuckClean)
                fetchRobolectricRuntimeDeps.setDescription("Fetches runtime dependencies for robolectric")

                fetchRobolectricRuntimeDeps.doLast {
                    RobolectricUtil.download(project)
                }
            }

            BuckWrapperTask buckWrapper = project.tasks.create(BUCK_WRAPPER, BuckWrapperTask, {
                repo = wrapper.repo
                remove = wrapper.remove
                keep = wrapper.keep
                watch = wrapper.watch
                sourceRoots = wrapper.sourceRoots
            })
            buckWrapper.setGroup(GROUP)
            buckWrapper.setDescription("Create buck wrapper")

            if (experimental.lint) {
                okbuck.buckProjects.each { Project buckProject ->
                    buckProject.configurations.maybeCreate(BUCK_LINT)
                }

                Task fetchLintDeps = project.task('fetchLintDeps')
                okBuck.dependsOn(fetchLintDeps)
                fetchLintDeps.mustRunAfter(okBuckClean)
                fetchLintDeps.doLast {
                    LintUtil.fetchLintDeps(project, lint.version)
                }
            }

            if (experimental.transform) {
                Task fetchTransformDeps = project.task('fetchTransformDeps')
                okBuck.dependsOn(fetchTransformDeps)
                fetchTransformDeps.mustRunAfter(okBuckClean)
                fetchTransformDeps.doLast { TransformUtil.fetchTransformDeps(project) }
            }

            if (experimental.retrolambda) {
                Task fetchRetrolambdaDeps = project.task('fetchRetrolambdaDeps')
                okBuck.dependsOn(fetchRetrolambdaDeps)
                fetchRetrolambdaDeps.mustRunAfter(okBuckClean)
                fetchRetrolambdaDeps.doLast {
                    RetrolambdaUtil.fetchRetrolambdaDeps(project, retrolambda)
                    okbuck.buckProjects.each { Project buckProject ->
                            RetrolambdaUtil.createRetrolambdac(buckProject)
                    }
                }
            }
        }
    }

    private static generate(Project project) {
        OkBuckExtension okbuck = project.okbuck

        // generate empty .buckconfig if it does not exist
        File dotBuckConfig = project.file(".buckconfig")
        if (!dotBuckConfig.exists()) {
            dotBuckConfig.createNewFile()
        }

        // generate .buckconfig.local
        File dotBuckConfigLocal = project.file(".buckconfig.local")
        PrintStream configPrinter = new PrintStream(dotBuckConfigLocal)
        DotBuckConfigLocalGenerator.generate(okbuck).print(configPrinter)
        IOUtils.closeQuietly(configPrinter)

        // generate BUCK file for each project
        Map<Project, BUCKFile> buckFiles = BuckFileGenerator.generate(project)

        buckFiles.each { Project subProject, BUCKFile buckFile ->
            PrintStream buckPrinter = new PrintStream(subProject.file(BUCK))
            buckFile.print(buckPrinter)
            IOUtils.closeQuietly(buckPrinter)
        }
    }
}
