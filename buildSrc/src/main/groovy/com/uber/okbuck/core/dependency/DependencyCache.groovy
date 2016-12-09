package com.uber.okbuck.core.dependency

import com.uber.okbuck.core.util.FileUtil
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.plugins.ide.internal.IdeDependenciesExtractor

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class DependencyCache {

    public static final String LOCAL_DEP_VERSION = "1.0.0"

    final Project rootProject
    final File cacheDir

    final boolean useFullDepName
    final boolean fetchSources
    final boolean extractLintJars

    private final Configuration configuration
    private final Map<VersionlessDependency, String> lintJars = [:]
    private final Map<VersionlessDependency, String> externalDeps = [:]

    DependencyCache(
            String name,
            Project rootProject,
            String cacheDirPath,
            Set<Configuration> configurations,
            String buckFile = null,
            boolean useFullDepName = false,
            boolean fetchSources = false,
            boolean extractLintJars = false) {

        this.rootProject = rootProject
        this.cacheDir = new File(rootProject.projectDir, cacheDirPath)
        this.cacheDir.mkdirs()

        Configuration superConfiguration = rootProject.configurations.maybeCreate("${name}DepCache")
        superConfiguration.setExtendsFrom(configurations)
        if (configurations.size() > 1) {
            this.configuration = superConfiguration.copyRecursive()
        } else {
            this.configuration = superConfiguration
        }

        if (buckFile) {
            FileUtil.copyResourceToProject(buckFile, new File(cacheDir, "BUCK"))
        }

        this.useFullDepName = useFullDepName
        this.fetchSources = fetchSources
        this.extractLintJars = extractLintJars
        build()
    }

    String get(ExternalDependency dependency) {
        return externalDeps.get(dependency)
    }

    private void build() {
        Set<File> resolvedFiles = [] as Set
        Set<ExternalDependency> allExtDeps = [] as Set
        configuration.resolvedConfiguration.resolvedArtifacts.each { ResolvedArtifact artifact ->
            String identifier = artifact.id.componentIdentifier.displayName
            File dep = artifact.file
            resolvedFiles.add(dep)

            if (!identifier.contains(" ")) {
                ExternalDependency dependency = new ExternalDependency(artifact.moduleVersion.id, dep)
                allExtDeps.add(dependency)
            }
        }

        configuration.files.findAll { File resolved ->
            !resolvedFiles.contains(resolved)
        }.each { File localDep ->
            String baseName = FilenameUtils.getBaseName(localDep.name)
            ModuleVersionIdentifier identifier = getDepIdentifier(
                    baseName,
                    baseName,
                    LOCAL_DEP_VERSION)

            ExternalDependency dependency = new ExternalDependency(identifier, localDep)
            allExtDeps.add(dependency)
        }

        // Download sources if enabled
        if (fetchSources) {
            new IdeDependenciesExtractor().extractRepoFileDependencies(
                    rootProject.dependencies,
                    [configuration],
                    [],
                    true,
                    false)
        }

        allExtDeps.each { ExternalDependency e ->
            File cachedCopy = new File(cacheDir, e.getCacheName(useFullDepName))

            // Copy the file into the cache
            if (!cachedCopy.exists()) {
                Files.copy(e.depFile.toPath(), cachedCopy.toPath())
            }

            String path = FileUtil.getRelativePath(rootProject.projectDir, cachedCopy)
            externalDeps.put(e, path)

            // Extract Lint Jars
            if (extractLintJars && cachedCopy.name.endsWith(".aar")) {
                File lintJar = getPackagedLintJar(cachedCopy)
                if (lintJar != null) {
                    String lintJarPath = FileUtil.getRelativePath(rootProject.projectDir, lintJar)
                    lintJars.put(e, lintJarPath)
                }
            }

            // Fetch Sources
            if (fetchSources) {
                fetchSourcesFor(e)
            }
        }
    }

    String getLintJar(ExternalDependency dependency) {
        return lintJars.get(dependency)
    }

    private void fetchSourcesFor(ExternalDependency dependency) {
        String sourcesJarName = dependency.depFile.name.replaceFirst(/\.(jar|aar)$/, ExternalDependency.SOURCES_JAR)

        File sourcesJar = null
        if (FileUtils.directoryContains(rootProject.projectDir, dependency.depFile)) {
            sourcesJar = new File(dependency.depFile.parentFile, sourcesJarName)
        } else {
            def sourceJars = rootProject.fileTree(
                    dir: dependency.depFile.parentFile.parentFile.absolutePath,
                    includes: ["**/${sourcesJarName}"]) as List
            if (sourceJars.size() > 0) {
                sourcesJar = sourceJars[0]
            }
        }

        File cachedCopy = new File(cacheDir, dependency.getSourceCacheName(useFullDepName))
        if (sourcesJar != null && sourcesJar.exists() && !cachedCopy.exists()) {
            FileUtils.copyFile(sourcesJar, cachedCopy)
        }
    }

    static File getPackagedLintJar(File aar) {
        File lintJar = new File(aar.parentFile, aar.name.replaceFirst(/\.aar$/, '-lint.jar'))
        if (lintJar.exists()) {
            return lintJar
        }
        FileSystem zipFile = FileSystems.newFileSystem(aar.toPath(), null)
        Path packagedLintJar = zipFile.getPath("lint.jar")
        if (Files.exists(packagedLintJar)) {
            Files.copy(packagedLintJar, lintJar.toPath())
            return lintJar
        } else {
            return null
        }
    }

    static ModuleVersionIdentifier getDepIdentifier(String group, String name, String version) {
        return new ModuleVersionIdentifier() {

            @Override
            String getVersion() {
                return version
            }

            @Override
            String getGroup() {
                return group
            }

            @Override
            String getName() {
                return name
            }

            @Override
            ModuleIdentifier getModule() {
                return null
            }
        }
    }
}
