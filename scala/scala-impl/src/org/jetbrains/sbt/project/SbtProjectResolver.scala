package org.jetbrains.sbt
package project

import java.io.{File, FileNotFoundException}
import java.util.UUID

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.project.{ProjectData => ESProjectData, _}
import com.intellij.openapi.externalSystem.model.task.event._
import com.intellij.openapi.externalSystem.model.task.{ExternalSystemTaskId, ExternalSystemTaskNotificationListener}
import com.intellij.openapi.externalSystem.model.{DataNode, ExternalSystemException}
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.plugins.scala.project.Version
import org.jetbrains.sbt.SbtUtil.{binaryVersion, detectSbtVersion}
import org.jetbrains.sbt.project.SbtProjectResolver._
import org.jetbrains.sbt.project.data._
import org.jetbrains.sbt.project.module.SbtModuleType
import org.jetbrains.sbt.project.settings._
import org.jetbrains.sbt.project.structure._
import org.jetbrains.sbt.resolvers.{SbtMavenResolver, SbtResolver}
import org.jetbrains.sbt.shell.SbtShellCommunication
import org.jetbrains.sbt.structure.ProjectData
import org.jetbrains.sbt.structure.XmlSerializer._
import org.jetbrains.sbt.{structure => sbtStructure}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, XML}

/**
 * @author Pavel Fatin
 */
class SbtProjectResolver extends ExternalSystemProjectResolver[SbtExecutionSettings] with ExternalSourceRootResolution {

  @volatile private var processDumper: Option[SbtProcessDump] = None

  override def resolveProjectInfo(taskId: ExternalSystemTaskId,
                                  wrongProjectPathDontUseIt: String,
                                  isPreview: Boolean,
                                  settings: SbtExecutionSettings,
                                  listener: ExternalSystemTaskNotificationListener): DataNode[ESProjectData] = {

    val root = {
      val file = new File(settings.realProjectPath)
      if (file.isDirectory) file else file.getParentFile
    }

    val options = if (isPreview) Seq.empty else dumpOptions(!isPreview, settings)

    val sbtLauncher = settings.customLauncher.getOrElse(getDefaultLauncher)
    val sbtVersion = Version(detectSbtVersion(root, sbtLauncher))
    lazy val project = taskId.findProject()
    val useShellImport = settings.useShellForImport && shellImportSupported(sbtVersion) && project != null

    val importTaskId = s"import:${UUID.randomUUID()}"
    val importTaskDescriptor =
      new TaskOperationDescriptorImpl("import to IntelliJ project model", System.currentTimeMillis(), "project-model-import")

    val structureDump: Try[(Elem, String)] =
      if (!sbtLauncher.isFile) {
        val error = s"sbt launcher not found at ${sbtLauncher.getCanonicalPath}"
        Failure(new FileNotFoundException(error))
      } else if (!importSupported(sbtVersion)) {
        val message = s"sbt $sinceSbtVersion+ required. Please update project build.properties."
        Failure(new UnsupportedOperationException(message))
      }
      else usingTempFile("sbt-structure", Some(".xml")) { structureFile =>
        val structureFilePath = path(structureFile)
        val launcherDir = getSbtLauncherDir
        val sbtStructureVersion = binaryVersion(sbtVersion).major(2).presentation

        val messageResult: Try[String] = {
          if (useShellImport) {
            val shell = SbtShellCommunication.forProject(project)
            SbtShellDump.dumpFromShell(taskId, root, shell, structureFilePath, options, listener)
          }
          else {
            val sbtStructureJar = settings.customSbtStructureFile.getOrElse(launcherDir / s"sbt-structure-$sbtStructureVersion.jar")
            val structureFilePath = path(structureFile)
            val dumper = new SbtProcessDump()
            processDumper = Option(dumper)
            dumper.dumpFromProcess(
              root, structureFilePath, options,
              settings.vmExecutable, settings.vmOptions, settings.environment,
              sbtLauncher, sbtStructureJar, taskId, listener)
          }
        }
        processDumper = None

        messageResult.flatMap { messages =>
          if (structureFile.length > 0) Try {
            val elem = XML.load(structureFile.toURI.toURL)
            (elem, messages)
          }
          else Failure(SbtException.fromSbtLog(messages))
        }
      }


    // side-effecty status reporting
    // TODO move to dump process for real-time feedback
    structureDump.foreach { case (_, messages) =>
      val convertStartEvent = new ExternalSystemStartEventImpl(importTaskId, null, importTaskDescriptor)
      val event = new ExternalSystemTaskExecutionEvent(taskId, convertStartEvent)
      listener.onStatusChange(event)

      val warnings = messages.lines.filter(isWarningOrError)
      if (warnings.nonEmpty)
        listener.onTaskOutput(taskId, WarningMessage(warnings.mkString(System.lineSeparator)), false)
    }

    val conversionResult = structureDump
      .map { case (elem, _) =>
        val data = elem.deserialize[sbtStructure.StructureData].right.get
        val warningsCallback: String=>Unit = msg => listener.onTaskOutput(taskId, msg, false) // TODO build-toolwindow compatible callback
        convert(path(root), data, settings.jdk, warningsCallback).toDataNode
      }
      .recoverWith {
        case ImportCancelledException =>
          // sorry, ExternalSystem expects a null when resolving is not possible
          Success(null)
        case x: Exception =>
          Failure(new ExternalSystemException(x))
      }

    // more side-effecty reporting
    conversionResult.transform (
      _ => Success(new SuccessResultImpl(0, System.currentTimeMillis(), true)), /* TODO starttime*/
      x => Success(
        new FailureResultImpl(0, System.currentTimeMillis(),
          List.empty[com.intellij.openapi.externalSystem.model.task.event.Failure].asJava // TODO error list
        )
      )
    ).foreach { result =>
      val convertFinishedEvent = new ExternalSystemFinishEventImpl[TaskOperationDescriptor](
        importTaskId, null, importTaskDescriptor, result
      )
      val event = new ExternalSystemTaskExecutionEvent(taskId, convertFinishedEvent)
      listener.onStatusChange(event)
    }

    conversionResult.get // ok to throw here, that's the way ExternalSystem likes it

  }

  private def dumpOptions(download: Boolean, settings: SbtExecutionSettings): Seq[String] = {
    download.seq("download") ++
      settings.resolveClassifiers.seq("resolveClassifiers") ++
      settings.resolveJavadocs.seq("resolveJavadocs") ++
      settings.resolveSbtClassifiers.seq("resolveSbtClassifiers")
  }

  private def isWarningOrError(message: String) =
    message.startsWith("[error] ") || message.startsWith("[warn] ")

  private def convert(root: String,
                      data: sbtStructure.StructureData,
                      settingsJdk: Option[String],
                      warnings: String => Unit): Node[ESProjectData] = {
    val projects: Seq[sbtStructure.ProjectData] = data.projects
    val rootProject: sbtStructure.ProjectData =
      projects.find(p => FileUtil.filesEqual(p.base, new File(root)))
        .orElse(projects.headOption)
        .getOrElse(throw new RuntimeException("No root project found"))
    val projectNode = new ProjectNode(rootProject.name, root, root)

    val basePackages = projects.flatMap(_.basePackages).distinct
    val javacOptions = rootProject.java.map(_.options).getOrElse(Seq.empty)

    val projectJdk = chooseJdk(rootProject, settingsJdk)

    projectNode.add(new SbtProjectNode(SbtProjectData(basePackages, projectJdk, javacOptions, data.sbtVersion, root)))

    val newPlay2Data = projects.flatMap(p => p.play2.map(d => (p.id, p.base, d)))
    projectNode.add(new Play2ProjectNode(Play2OldStructureAdapter(newPlay2Data)))

    val libraryNodes = createLibraries(data, projects)
    projectNode.addAll(libraryNodes)

    val moduleFilesDirectory = new File(root + "/" + Sbt.ModulesDirectory)
    val moduleNodes = createModules(projects, libraryNodes, moduleFilesDirectory)

    projectNode.addAll(moduleNodes)

    val projectToModuleNode: Map[sbtStructure.ProjectData, ModuleNode] = projects.zip(moduleNodes).toMap

    val sharedSourceModules = createSharedSourceModules(projectToModuleNode, libraryNodes, moduleFilesDirectory, warnings)
    projectNode.addAll(sharedSourceModules)

    val buildModuleForProject: (ProjectData) => ModuleNode = createBuildModule(_, moduleFilesDirectory, data.localCachePath.map(_.getCanonicalPath))
    projectNode.addAll(allBuildModules(rootProject, projects, buildModuleForProject))
    projectNode
  }

  /** Choose a project jdk based on information from sbt settings and IDE.
    * More specific settings from sbt are preferred over IDE settings, on the assumption that the sbt project definition
    * is what is more likely to be under source control.
    */
  private def chooseJdk(project: sbtStructure.ProjectData, defaultJdk: Option[String]): Option[Sdk] = {
    // TODO put some of this logic elsewhere in resolving process?
    val androidSdk = project.android.map(android => Android(android.targetVersion))
    val jdkHomeInSbtProject = project.java.flatMap(_.home).map(JdkByHome)

    // default either from project structure or initial import settings
    val default = defaultJdk.map(JdkByName)

    androidSdk
      .orElse(jdkHomeInSbtProject)
      .orElse(default)
  }

  private def allBuildModules(rootProject: sbtStructure.ProjectData, projects: Seq[sbtStructure.ProjectData], buildModule: (ProjectData) => ModuleNode) = {

    val rootBuildModule = buildModule(rootProject)
    projects.map { p =>
      val mod = buildModule(p)

      // subprojects of the main root project inherit the build definitions classpath
      if (p.id != rootProject.id && p.buildURI == rootProject.buildURI) {
        val depNode = new ModuleDependencyNode(mod, rootBuildModule)
        mod.add(depNode)
      }
      mod
    }
  }

  private def createModuleDependencies(projects: Seq[sbtStructure.ProjectData], moduleNodes: Seq[ModuleNode]): Unit = {
    projects.zip(moduleNodes).foreach { case (moduleProject, moduleNode) =>
      moduleProject.dependencies.projects.foreach { dependencyId =>
        val dependency = moduleNodes.find(_.getId == dependencyId.project).getOrElse(
          throw new ExternalSystemException("Cannot find project dependency: " + dependencyId.project))
        val data = new ModuleDependencyNode(moduleNode, dependency)
        data.setScope(scopeFor(dependencyId.configuration))
        data.setExported(true)
        moduleNode.add(data)
      }
    }
  }

  private def createModules(projects: Seq[sbtStructure.ProjectData], libraryNodes: Seq[LibraryNode], moduleFilesDirectory: File): Seq[ModuleNode] = {
    val unmanagedSourcesAndDocsLibrary = libraryNodes.map(_.data).find(_.getExternalName == Sbt.UnmanagedSourcesAndDocsName)
    val modules = projects.map { project =>
      val moduleNode = createModule(project, moduleFilesDirectory)
      val contentRootNode = createContentRoot(project)
      project.android.foreach(a => a.apklibs.foreach(addApklibDirs(contentRootNode, _)))
      moduleNode.add(contentRootNode)
      moduleNode.addAll(createLibraryDependencies(project.dependencies.modules)(moduleNode, libraryNodes.map(_.data)))
      moduleNode.add(createModuleExtData(project))
      moduleNode.add(new SbtModuleNode(SbtModuleData(project.id, project.buildURI)))
      moduleNode.addAll(createTaskData(project))
      moduleNode.addAll(createSettingData(project))
      moduleNode.addAll(createCommandData(project))
      moduleNode.addAll(project.android.map(createFacet(project, _)).toSeq)
      moduleNode.addAll(createUnmanagedDependencies(project.dependencies.jars)(moduleNode))
      unmanagedSourcesAndDocsLibrary foreach { lib =>
        val dependency = new LibraryDependencyNode(moduleNode, lib, LibraryLevel.MODULE)
        dependency.setScope(DependencyScope.COMPILE)
        moduleNode.add(dependency)
      }
      moduleNode
    }

    createModuleDependencies(projects, modules)

    modules
  }

  private def createLibraries(data: sbtStructure.StructureData, projects: Seq[sbtStructure.ProjectData]): Seq[LibraryNode] = {
    val repositoryModules = data.repository.map(_.modules).getOrElse(Seq.empty)
    val (modulesWithoutBinaries, modulesWithBinaries) = repositoryModules.partition(_.binaries.isEmpty)
    val otherModuleIds = projects.flatMap(_.dependencies.modules.map(_.id)).toSet --
            repositoryModules.map(_.id).toSet

    val libs = modulesWithBinaries.map(createResolvedLibrary) ++ otherModuleIds.map(createUnresolvedLibrary)

    val modulesWithDocumentation = modulesWithoutBinaries.filter(m => m.docs.nonEmpty || m.sources.nonEmpty)
    if (modulesWithDocumentation.isEmpty) return libs

    val unmanagedSourceLibrary = new LibraryNode(Sbt.UnmanagedSourcesAndDocsName, true)
    unmanagedSourceLibrary.addPaths(LibraryPathType.DOC, modulesWithDocumentation.flatMap(_.docs).map(_.path))
    unmanagedSourceLibrary.addPaths(LibraryPathType.SOURCE, modulesWithDocumentation.flatMap(_.sources).map(_.path))
    libs :+ unmanagedSourceLibrary
  }

  private def createModuleExtData(project: sbtStructure.ProjectData): ModuleExtNode = {
    val scalaOrganization = project.scala.map(_.organization).getOrElse("org.scala-lang")
    val scalaVersion = project.scala.map(s => Version(s.version))
    val scalacClasspath = project.scala.fold(Seq.empty[File])(s => s.jars)
    val scalacOptions = project.scala.fold(Seq.empty[String])(_.options)
    val javacOptions = project.java.fold(Seq.empty[String])(_.options)
    val jdk = project.android.map(android => Android(android.targetVersion))
      .orElse(project.java.flatMap(java => java.home.map(JdkByHome)))
    new ModuleExtNode(ModuleExtData(scalaOrganization, scalaVersion, scalacClasspath, scalacOptions, jdk, javacOptions))
  }


  private def createTaskData(project: sbtStructure.ProjectData): Seq[SbtTaskNode] = {
    project.tasks.map { t =>
      new SbtTaskNode(SbtTaskData(t.label, t.description.getOrElse(""), t.rank))
    }
  }

  private def createSettingData(project: sbtStructure.ProjectData): Seq[SbtSettingNode] = {
    project.settings.map { s =>
      // TODO use options for description, value and handle them in the UI appropriately
      new SbtSettingNode(SbtSettingData(s.label, s.description.getOrElse(""), s.rank, s.stringValue.getOrElse("")))
    }
  }

  private def createCommandData(project: sbtStructure.ProjectData) = {
     project.commands.map { c =>
      new SbtCommandNode(SbtCommandData(c.name, c.help))
    }
  }

  private def createFacet(project: sbtStructure.ProjectData, android: sbtStructure.AndroidData): AndroidFacetNode = {
    new AndroidFacetNode(AndroidFacetData(android.targetVersion, android.manifest, android.apk,
                         android.res, android.assets, android.gen, android.libs,
                         android.isLibrary, android.proguardConfig))
  }

  private def createUnresolvedLibrary(moduleId: sbtStructure.ModuleIdentifier): LibraryNode = {
    val module = sbtStructure.ModuleData(moduleId, Set.empty, Set.empty, Set.empty)
    createLibrary(module, resolved = false)
  }

  private def createResolvedLibrary(module: sbtStructure.ModuleData): LibraryNode = {
    createLibrary(module, resolved = true)
  }

  private def createLibrary(module: sbtStructure.ModuleData, resolved: Boolean): LibraryNode = {
    val result = new LibraryNode(nameFor(module.id), resolved)
    result.addPaths(LibraryPathType.BINARY, module.binaries.map(_.path).toSeq)
    result.addPaths(LibraryPathType.DOC, module.docs.map(_.path).toSeq)
    result.addPaths(LibraryPathType.SOURCE, module.sources.map(_.path).toSeq)
    result
  }

  private def nameFor(id: sbtStructure.ModuleIdentifier) = {
    val classifierOption = if (id.classifier.isEmpty) None else Some(id.classifier)
    s"${id.organization}:${id.name}:${id.revision}" + classifierOption.map(":"+_).getOrElse("") + s":${id.artifactType}"
  }

  private def createModule(project: sbtStructure.ProjectData, moduleFilesDirectory: File): ModuleNode = {
    // TODO use both ID and Name when related flaws in the External System will be fixed
    // TODO explicit canonical path is needed until IDEA-126011 is fixed
    val result = new ModuleNode(StdModuleTypes.JAVA.getId, project.id, project.id,
      moduleFilesDirectory.path, project.base.canonicalPath)

    result.setInheritProjectCompileOutputPath(false)

    project.configurations.find(_.id == "compile").foreach { configuration =>
      result.setCompileOutputPath(ExternalSystemSourceType.SOURCE, configuration.classes.path)
    }

    project.configurations.find(_.id == "test").foreach { configuration =>
      result.setCompileOutputPath(ExternalSystemSourceType.TEST, configuration.classes.path)
    }

    result
  }

  private def createContentRoot(project: sbtStructure.ProjectData): ContentRootNode = {
    val productionSources = validRootPathsIn(project, "compile")(_.sources)
    val productionResources = validRootPathsIn(project, "compile")(_.resources)
    val testSources = validRootPathsIn(project, "test")(_.sources) ++ validRootPathsIn(project, "it")(_.sources)
    val testResources = validRootPathsIn(project, "test")(_.resources) ++ validRootPathsIn(project, "it")(_.resources)

    val result = new ContentRootNode(project.base.path)

    result.storePaths(ExternalSystemSourceType.SOURCE, unmanagedDirectories(productionSources))
    result.storePaths(ExternalSystemSourceType.SOURCE_GENERATED, managedDirectories(productionSources))
    result.storePaths(ExternalSystemSourceType.RESOURCE, allDirectories(productionResources))

    result.storePaths(ExternalSystemSourceType.TEST, unmanagedDirectories(testSources))
    result.storePaths(ExternalSystemSourceType.TEST_GENERATED, managedDirectories(testSources))
    result.storePaths(ExternalSystemSourceType.TEST_RESOURCE, allDirectories(testResources))

    getExcludedTargetDirs(project).foreach { path =>
      result.storePath(ExternalSystemSourceType.EXCLUDED, path.path)
    }

    result
  }

  private def allDirectories(dirs: Seq[sbtStructure.DirectoryData]) =
    dirs.map(_.file.canonicalPath)

  private def managedDirectories(dirs: Seq[sbtStructure.DirectoryData]) =
    dirs.filter(_.managed).map(_.file.canonicalPath)

  private def unmanagedDirectories(dirs: Seq[sbtStructure.DirectoryData]) =
    dirs.filterNot(_.managed).map(_.file.canonicalPath)

  // We cannot always exclude the whole ./target/ directory because of
  // the generated sources, so we resort to an heuristics.
  private def getExcludedTargetDirs(project: sbtStructure.ProjectData): Seq[File] = {
    val extractedExcludes = project.configurations.flatMap(_.excludes)
    if (extractedExcludes.nonEmpty)
      return extractedExcludes.distinct

    val managedDirectories = project.configurations
            .flatMap(configuration => configuration.sources ++ configuration.resources)
            .filter(_.managed)
            .map(_.file)

    val defaultNames = Set("main", "test")

    val relevantDirectories = managedDirectories.filter(file => file.exists || !defaultNames.contains(file.getName))
    def isRelevant(f: File): Boolean = !relevantDirectories.forall(_.isOutsideOf(f))

    if (isRelevant(project.target)) {
      // If we can't exclude the target directory, go one level deeper (which may hit resolution-cache and streams)
      Option(project.target.listFiles()).toList.flatten.filter {
        child => child.isDirectory && !isRelevant(child)
      }
    } else List(project.target)
  }

  // TODO where do I add build module cross-dependencies so that code is resolved correctly?
  private def createBuildModule(project: sbtStructure.ProjectData, moduleFilesDirectory: File, localCachePath: Option[String]): ModuleNode = {
    val id = project.id + Sbt.BuildModuleSuffix
    val buildRoot = project.base / Sbt.ProjectDirectory

    // TODO use both ID and Name when related flaws in the External System will be fixed
    // TODO explicit canonical path is needed until IDEA-126011 is fixed
    val result = new ModuleNode(SbtModuleType.instance.getId, id, id, moduleFilesDirectory.path, buildRoot.canonicalPath)

    result.setInheritProjectCompileOutputPath(false)
    result.setCompileOutputPath(ExternalSystemSourceType.SOURCE, (buildRoot / Sbt.TargetDirectory / "idea-classes").path)
    result.setCompileOutputPath(ExternalSystemSourceType.TEST, (buildRoot / Sbt.TargetDirectory / "idea-test-classes").path)

    result.add(createBuildContentRoot(buildRoot))

    val library = {
      val build = project.build
      val classes = build.classes.filter(_.exists).map(_.path)
      val docs = build.docs.filter(_.exists).map(_.path)
      val sources = build.sources.filter(_.exists).map(_.path)
      createModuleLevelDependency(Sbt.BuildLibraryName, classes, docs, sources, DependencyScope.COMPILE)(result)
    }

    result.add(library)

    result.add(createSbtBuildModuleData(project, localCachePath))

    result
  }

  private def createBuildContentRoot(buildRoot: File): ContentRootNode = {
    val result = new ContentRootNode(buildRoot.path)

    val sourceDirs = Seq(buildRoot) // , base << 1

    val exludedDirs = Seq(
      buildRoot / Sbt.TargetDirectory,
      buildRoot / Sbt.ProjectDirectory / Sbt.TargetDirectory)

    result.storePaths(ExternalSystemSourceType.SOURCE, sourceDirs.map(_.path))
    result.storePaths(ExternalSystemSourceType.EXCLUDED, exludedDirs.map(_.path))

    result
  }

  private def createSbtBuildModuleData(project: sbtStructure.ProjectData, localCachePath: Option[String]): SbtBuildModuleNode = {
    val imports = project.build.imports.flatMap(_.trim.substring(7).split(", "))
    val resolvers = project.resolvers map { r => new SbtMavenResolver(r.name, r.root).asInstanceOf[SbtResolver] }
    new SbtBuildModuleNode(SbtBuildModuleData(imports, resolvers + SbtResolver.localCacheResolver(localCachePath)))
  }

  private def validRootPathsIn(project: sbtStructure.ProjectData, scope: String)
                              (selector: sbtStructure.ConfigurationData => Seq[sbtStructure.DirectoryData]): Seq[sbtStructure.DirectoryData] = {
    project.configurations
            .find(_.id == scope)
            .map(selector)
            .getOrElse(Seq.empty)
            .filterNot(_.file.isOutsideOf(project.base))
  }

  protected def createLibraryDependencies(dependencies: Seq[sbtStructure.ModuleDependencyData])
      (moduleData: ModuleData, libraries: Seq[LibraryData]): Seq[LibraryDependencyNode] = {
    dependencies.map { dependency =>
      val name = nameFor(dependency.id)
      val library = libraries.find(_.getExternalName == name).getOrElse(
        throw new ExternalSystemException("Library not found: " + name))
      val data = new LibraryDependencyNode(moduleData, library, LibraryLevel.PROJECT)
      data.setScope(scopeFor(dependency.configurations))
      data
    }
  }

  private def createUnmanagedDependencies(dependencies: Seq[sbtStructure.JarDependencyData])
      (moduleData: ModuleData): Seq[LibraryDependencyNode] = {
    dependencies.groupBy(it => scopeFor(it.configurations)).toSeq.map { case (scope, dependency) =>
      val name = scope match {
        case DependencyScope.COMPILE => Sbt.UnmanagedLibraryName
        case it => s"${Sbt.UnmanagedLibraryName}-${it.getDisplayName.toLowerCase}"
      }
      val files = dependency.map(_.file.path)
      createModuleLevelDependency(name, files, Seq.empty, Seq.empty, scope)(moduleData)
    }
  }

  private def createModuleLevelDependency(name: String, classes: Seq[String], docs: Seq[String], sources: Seq[String], scope: DependencyScope)
                                         (moduleData: ModuleData): LibraryDependencyNode = {

    val libraryNode = new LibraryNode(name, resolved = true)
    libraryNode.addPaths(LibraryPathType.BINARY, classes)
    libraryNode.addPaths(LibraryPathType.DOC, docs)
    libraryNode.addPaths(LibraryPathType.SOURCE, sources)

    val result = new LibraryDependencyNode(moduleData, libraryNode, LibraryLevel.MODULE)
    result.setScope(scope)
    result
  }

  private def addApklibDirs(contentRootNode: ContentRootNode, apklib: sbtStructure.ApkLib): Unit = {
    contentRootNode.storePath(ExternalSystemSourceType.SOURCE, apklib.sources.canonicalPath)
    contentRootNode.storePath(ExternalSystemSourceType.SOURCE_GENERATED, apklib.gen.canonicalPath)
    contentRootNode.storePath(ExternalSystemSourceType.RESOURCE, apklib.resources.canonicalPath)
  }

  protected def scopeFor(configurations: Seq[sbtStructure.Configuration]): DependencyScope = {
    val ids = configurations.toSet

    if (ids.contains(sbtStructure.Configuration.Compile))
      DependencyScope.COMPILE
    else if (ids.contains(sbtStructure.Configuration.Runtime))
      DependencyScope.RUNTIME
    else if (ids.contains(sbtStructure.Configuration.Test))
      DependencyScope.TEST
    else if (ids.contains(sbtStructure.Configuration.Provided))
      DependencyScope.PROVIDED
    else
      DependencyScope.COMPILE
  }

  override def cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean =
    //noinspection UnitInMap
    processDumper
      .map(_.cancel())
      .isDefined

}

object SbtProjectResolver {


  case object ImportCancelledException extends Exception

  val SBT_PROCESS_CHECK_TIMEOUT_MSEC = 100

  private def isInTest: Boolean = ApplicationManager.getApplication.isUnitTestMode

  // TODO shared code, move to a more suitable object
  def getSbtLauncherDir: File = {
    val file: File = jarWith[this.type]
    val deep = if (file.getName == "classes") 1 else 2
    val res = (file << deep) / "launcher"
    if (!res.exists() && isInTest) {
      val start = jarWith[this.type].parent
      start.flatMap(findLauncherDir)
        .getOrElse(throw new RuntimeException(s"could not find sbt launcher dir at or above ${start.get}"))
    }
    else res
  }

  // TODO shared code, move to a more suitable object
  def getDefaultLauncher: File = getSbtLauncherDir / "sbt-launch.jar"

  // TODO shared code, move to a more suitable object
  def path(file: File): String = file.getAbsolutePath.replace('\\', '/')

  def shellImportSupported(sbtVersion: Version): Boolean =
    sbtVersion >= sinceSbtVersionShell

  def importSupported(sbtVersion: Version): Boolean =
    sbtVersion >= sinceSbtVersion

  private def findLauncherDir(from: File): Option[File] = {
    val launcherDir = from / "target" / "plugin" / "Scala" / "launcher"
    if (launcherDir.exists) Option(launcherDir)
    else from.parent.flatMap(findLauncherDir)
  }

  // TODO shared code, move to a more suitable object
  val sinceSbtVersion = Version("0.12.4")

  // TODO shared code, move to a more suitable object
  val sinceSbtVersionShell = Version("0.13.5")

}
