package com.uber.okbuck.core.dependency

import com.uber.okbuck.core.util.FileUtil
import groovy.transform.Synchronized
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.plugins.ide.internal.IdeDependenciesExtractor

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile

class DependencyCache {

    final Project rootProject
    final File cacheDir

    final boolean useFullDepName
    final boolean fetchSources
    final boolean extractLintJars

    private final Configuration superConfiguration
    private final Map<VersionlessDependency, String> lintJars = [:]
    private final Map<VersionlessDependency, String> externalDeps = [:]
    private final Map<VersionlessDependency, Set<String>> annotationProcessors = [:]
    private final Map<VersionlessDependency, ExternalDependency> greatestVersions = [:]
    private final Set<File> cachedCopies = [] as Set

    DependencyCache(
            String name,
            Project rootProject,
            String cacheDirPath,
            Set<Configuration> configurations,
            String buckFile = null,
            boolean cleanup = true,
            boolean useFullDepName = false,
            boolean fetchSources = false,
            boolean extractLintJars = false) {

        this.rootProject = rootProject
        this.cacheDir = new File(rootProject.projectDir, cacheDirPath)
        this.cacheDir.mkdirs()

        superConfiguration = createSuperConfiguration(rootProject, "${name}DepCache", configurations)

        if (buckFile) {
            FileUtil.copyResourceToProject(buckFile, new File(cacheDir, "BUCK"))
        }

        this.useFullDepName = useFullDepName
        this.fetchSources = fetchSources
        this.extractLintJars = extractLintJars
        build(cleanup)
    }

    String get(ExternalDependency dependency) {
        String dep = externalDeps.get(dependency)
        if (dep == null) {
            throw new IllegalStateException("Could not find dependency path for ${dependency}")
        }
        return dep
    }

    @Synchronized
    Set<String> getAnnotationProcessors(ExternalDependency dependency) {
        ExternalDependency greatest = greatestVersions.get(dependency)
        if (greatest.depFile.name.endsWith(".jar")) {
            Set<String> processors = annotationProcessors.get(greatest)
            if (!processors) {
                File cachedCopy = new File(cacheDir, greatest.getCacheName(useFullDepName))
                processors = getAnnotationProcessorsFile(cachedCopy).text.split('\n')
                annotationProcessors.put(greatest, processors)
            }
            return processors
        } else {
            return []
        }
    }

    private void build(boolean cleanup) {
        Set<File> resolvedFiles = [] as Set
        Set<ExternalDependency> allExtDeps = [] as Set
        superConfiguration.resolvedConfiguration.resolvedArtifacts.each { ResolvedArtifact artifact ->
            String identifier = artifact.id.componentIdentifier.displayName
            File dep = artifact.file
            resolvedFiles.add(dep)

            if (!identifier.contains(" ")) {
                ExternalDependency dependency = new ExternalDependency(artifact.moduleVersion.id, dep)
                allExtDeps.add(dependency)
            }
        }

        superConfiguration.files.findAll { File resolved ->
            !resolvedFiles.contains(resolved)
        }.each { File localDep ->
            allExtDeps.add(ExternalDependency.fromLocal(localDep))
        }

        // Download sources if enabled
        if (fetchSources) {
            new IdeDependenciesExtractor().extractRepoFileDependencies(
                    rootProject.dependencies,
                    [superConfiguration],
                    [],
                    true,
                    false)
        }

        allExtDeps.each { ExternalDependency e ->
            greatestVersions.put(e, e)
            File cachedCopy = new File(cacheDir, e.getCacheName(useFullDepName))

            // Copy the file into the cache
            if (!cachedCopy.exists()) {
                Files.copy(e.depFile.toPath(), cachedCopy.toPath())
            }
            cachedCopies.add(cachedCopy)

            String path = FileUtil.getRelativePath(rootProject.projectDir, cachedCopy)
            externalDeps.put(e, path)

            // Extract Lint Jars
            if (extractLintJars && cachedCopy.name.endsWith(".aar")) {
                File lintJar = getPackagedLintJar(cachedCopy)
                if (lintJar != null) {
                    String lintJarPath = FileUtil.getRelativePath(rootProject.projectDir, lintJar)
                    lintJars.put(e, lintJarPath)
                    cachedCopies.add(lintJar)
                }
            }

            // Fetch Sources
            if (fetchSources) {
                fetchSourcesFor(e)
            }
        }

        // cleanup
        if (cleanup) {
            (cacheDir.listFiles(new FileFilter() {

                @Override
                boolean accept(File pathname) {
                    return pathname.isFile() && (pathname.name.endsWith(".jar") || pathname.name.endsWith(".aar"))
                }
            }) - cachedCopies).each { File f ->
                f.delete()
            }
        }
    }

    private static Configuration createSuperConfiguration(Project project, String superConfigName,
                                                          Set<Configuration> configurations) {
        Configuration superConfiguration = project.configurations.maybeCreate(superConfigName)
        configurations.each {
            superConfiguration.dependencies.addAll(it.dependencies as Set)
        }
        return superConfiguration
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
        cachedCopies.add(cachedCopy)
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

    static File getAnnotationProcessorsFile(File jar) {
        File processors = new File(jar.parentFile, jar.name.replaceFirst(/\.jar$/, '.processors'))
        if (processors.exists()) {
            return processors
        }

        JarFile jarFile = new JarFile(jar)
        JarEntry jarEntry = (JarEntry) jarFile.getEntry("META-INF/services/javax.annotation.processing.Processor")
        if (jarEntry) {
            List<String> processorClasses = IOUtils.toString(jarFile.getInputStream(jarEntry))
                    .trim().split("\\n").findAll { String entry ->
                !entry.startsWith('#') && !entry.trim().empty // filter out comments and empty lines
            }
            processors.text = processorClasses.join('\n')
        } else {
            processors.createNewFile()
        }
        return processors
    }
}
