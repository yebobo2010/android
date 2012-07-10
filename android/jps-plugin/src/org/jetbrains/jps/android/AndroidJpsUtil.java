package org.jetbrains.jps.android;

import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.SdkConstants;
import com.android.sdklib.SdkManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Processor;
import com.intellij.util.containers.HashSet;
import org.jetbrains.android.sdk.MessageBuildingSdkLog;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.AndroidCompilerMessageKind;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.*;
import org.jetbrains.jps.idea.Facet;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ModuleLevelBuilder;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.Utils;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;

/**
 * @author Eugene.Kudelevsky
 */
class AndroidJpsUtil {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.jps.android.AndroidJpsUtil");

  @NonNls public static final String ANDROID_STORAGE_DIR = "android";
  @NonNls private static final String RESOURCE_CACHE_STORAGE = "res_cache";
  @NonNls private static final String INTERMEDIATE_ARTIFACTS_STORAGE = "intermediate_artifacts";

  public static final Condition<File> CLASSES_AND_JARS_FILTER = new Condition<File>() {
    @Override
    public boolean value(File file) {
      final String ext = FileUtil.getExtension(file.getName());
      return "jar".equals(ext) || "class".equals(ext);
    }
  };
  @NonNls public static final String GENERATED_RESOURCES_DIR_NAME = "generated_resources";
  @NonNls public static final String AAPT_GENERATED_SOURCE_ROOT_NAME = "aapt";
  @NonNls public static final String AIDL_GENERATED_SOURCE_ROOT_NAME = "aidl";
  @NonNls public static final String RENDERSCRIPT_GENERATED_SOURCE_ROOT_NAME = "rs";
  @NonNls public static final String BUILD_CONFIG_GENERATED_SOURCE_ROOT_NAME = "build_config";
  @NonNls private static final String GENERATED_SOURCES_FOLDER_NAME = "generated_sources";

  private AndroidJpsUtil() {
  }

  public static boolean isMavenizedModule(Module module) {
    // todo: implement
    return false;
  }

  @Nullable
  public static File getMainContentRoot(@NotNull AndroidFacet facet) throws IOException {
    final Module module = facet.getModule();

    final List<String> contentRoots = module.getContentRoots();

    if (contentRoots.size() == 0) {
      return null;
    }
    final File manifestFile = facet.getManifestFile();

    if (manifestFile != null) {
      for (String rootPath : contentRoots) {
        final File root = new File(rootPath);

        if (FileUtil.isAncestor(root, manifestFile, true)) {
          return root;
        }
      }
    }
    return new File(contentRoots.get(0));
  }

  public static void addMessages(@NotNull CompileContext context,
                                 @NotNull Map<AndroidCompilerMessageKind, List<String>> messages,
                                 @NotNull String builderName,
                                 @NotNull String moduleName) {
    for (Map.Entry<AndroidCompilerMessageKind, List<String>> entry : messages.entrySet()) {
      for (String message : entry.getValue()) {
        String filePath = null;
        int line = -1;
        final Matcher matcher = AndroidCommonUtils.COMPILER_MESSAGE_PATTERN.matcher(message);

        if (matcher.matches()) {
          filePath = matcher.group(1);
          line = Integer.parseInt(matcher.group(2));
        }
        final BuildMessage.Kind category = toBuildMessageKind(entry.getKey());
        if (category != null) {
          context.processMessage(
            new CompilerMessage(builderName, category, '[' + moduleName + "] " + message, filePath, -1L, -1L, -1L, line, -1L));
        }
      }
    }
  }

  @Nullable
  public static AndroidFacet getFacet(@NotNull Module module) {
    AndroidFacet androidFacet = null;

    for (Facet facet : module.getFacets().values()) {
      if (facet instanceof AndroidFacet) {
        androidFacet = (AndroidFacet)facet;
      }
    }
    return androidFacet;
  }

  @NotNull
  public static String[] toPaths(@NotNull File[] files) {
    final String[] result = new String[files.length];

    for (int i = 0; i < result.length; i++) {
      result[i] = files[i].getPath();
    }
    return result;
  }

  @NotNull
  public static List<String> toPaths(@NotNull Collection<File> files) {
    if (files.size() == 0) {
      return Collections.emptyList();
    }

    final List<String> result = new ArrayList<String>(files.size());
    for (File file : files) {
      result.add(file.getPath());
    }
    return result;
  }

  @NotNull
  public static File getDirectoryForIntermediateArtifacts(@NotNull CompileContext context,
                                                          @NotNull Module module) {
    final File androidStorage = new File(context.getProjectDescriptor().dataManager.getDataStorageRoot(), ANDROID_STORAGE_DIR);
    return new File(new File(androidStorage, INTERMEDIATE_ARTIFACTS_STORAGE), module.getName());
  }

  @Nullable
  public static File createDirIfNotExist(@NotNull File dir, @NotNull CompileContext context, @NotNull String compilerName) {
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        context.processMessage(new CompilerMessage(compilerName, BuildMessage.Kind.ERROR,
                                                   AndroidJpsBundle.message("android.jps.cannot.create.directory", dir.getPath())));
        return null;
      }
    }
    return dir;
  }

  public static void addSubdirectories(@NotNull File baseDir, @NotNull Collection<String> result) {
    // only include files inside packages
    final File[] children = baseDir.listFiles();

    if (children != null) {
      for (File child : children) {
        if (child.isDirectory()) {
          result.add(child.getPath());
        }
      }
    }
  }

  @NotNull
  public static Set<String> getExternalLibraries(@NotNull CompileContext context,
                                                 @NotNull Module module,
                                                 @NotNull AndroidPlatform platform) {
    final Set<String> result = new HashSet<String>();
    final AndroidDependencyProcessor processor = new AndroidDependencyProcessor() {
      @Override
      public void processExternalLibrary(@NotNull File file) {
        result.add(file.getPath());
      }

      @Override
      public boolean isToProcess(@NotNull AndroidDependencyType type) {
        return type == AndroidDependencyType.EXTERNAL_LIBRARY;
      }
    };
    processClasspath(context, module, processor);
    addAnnotationsJarIfNecessary(platform, result);
    return result;
  }

  private static void addAnnotationsJarIfNecessary(@NotNull AndroidPlatform platform, @NotNull Set<String> libs) {
    if (platform.needToAddAnnotationsJarToClasspath()) {
      final String sdkHomePath = platform.getSdk().getSdkPath();
      final String annotationsJarPath = FileUtil.toSystemIndependentName(sdkHomePath) + AndroidCommonUtils.ANNOTATIONS_JAR_RELATIVE_PATH;

      if (new File(annotationsJarPath).exists()) {
        libs.add(annotationsJarPath);
      }
    }
  }

  public static void processClasspath(@NotNull CompileContext context,
                                      @NotNull Module module,
                                      @NotNull AndroidDependencyProcessor processor) {
    processClasspath(context, module, processor, new HashSet<String>(), false);
  }

  private static void processClasspath(@NotNull CompileContext context,
                                       @NotNull final Module module,
                                       @NotNull final AndroidDependencyProcessor processor,
                                       @NotNull final Set<String> visitedModules,
                                       final boolean exportedLibrariesOnly) {
    if (!visitedModules.add(module.getName())) {
      return;
    }
    final ProjectPaths paths = context.getProjectPaths();

    if (processor.isToProcess(AndroidDependencyType.EXTERNAL_LIBRARY)) {
      for (ClasspathItem item : module.getClasspath(ClasspathKind.PRODUCTION_RUNTIME, exportedLibrariesOnly)) {
        if (item instanceof Library && !(item instanceof Sdk)) {
          for (Object filePathObj : ((Library)item).getClasspath()) {
            final String filePath = (String)filePathObj;
            final File file = new File(filePath);

            if (file.exists()) {
              processClassFilesAndJarsRecursively(filePath, new Processor<File>() {
                @Override
                public boolean process(File file) {
                  processor.processExternalLibrary(file);
                  return true;
                }
              });
            }
          }
        }
      }
    }

    for (ClasspathItem item : module.getClasspath(ClasspathKind.PRODUCTION_RUNTIME, false)) {
      if (item instanceof Module) {
        final Module depModule = (Module)item;
        final AndroidFacet depFacet = getFacet(depModule);
        final boolean depLibrary = depFacet != null && depFacet.isLibrary();
        final File depClassDir = paths.getModuleOutputDir(depModule, false);

        if (depLibrary) {
          if (processor.isToProcess(AndroidDependencyType.ANDROID_LIBRARY_PACKAGE)) {
            final File intArtifactsDir = getDirectoryForIntermediateArtifacts(context, depModule);
            final File packagedClassesJar = new File(intArtifactsDir, AndroidCommonUtils.CLASSES_JAR_FILE_NAME);

            if (packagedClassesJar.isFile()) {
              processor.processAndroidLibraryPackage(packagedClassesJar);
            }
          }
          if (processor.isToProcess(AndroidDependencyType.ANDROID_LIBRARY_OUTPUT_DIRECTORY)) {
            if (depClassDir != null && depClassDir.isDirectory()) {
              processor.processAndroidLibraryOutputDirectory(depClassDir);
            }
          }
        }
        else if (processor.isToProcess(AndroidDependencyType.JAVA_MODULE_OUTPUT_DIR) &&
                 depFacet == null &&
                 depClassDir != null &&
                 depClassDir.isDirectory()) {
          // do not support android-app->android-app compile dependencies
          processor.processJavaModuleOutputDirectory(depClassDir);
        }
        processClasspath(context, depModule, processor, visitedModules, !depLibrary || exportedLibrariesOnly);
      }
    }
  }

  public static void processClassFilesAndJarsRecursively(@NotNull String root, @NotNull final Processor<File> processor) {
    FileUtil.processFilesRecursively(new File(root), new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (file.isFile()) {
          final String ext = FileUtil.getExtension(file.getName());

          // NOTE: we should ignore apklib dependencies (IDEA-82976)
          if ("jar".equals(ext) || "class".equals(ext)) {
            if (!processor.process(file)) {
              return false;
            }
          }
        }
        return true;
      }
    });
  }

  @Nullable
  public static IAndroidTarget parseAndroidTarget(@NotNull AndroidSdk sdk, @NotNull CompileContext context, @NotNull String builderName) {
    final String targetHashString = sdk.getBuildTargetHashString();
    if (targetHashString == null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 "Cannot parse SDK " + sdk.getName() + ": build target is not specified"));
      return null;
    }

    final MessageBuildingSdkLog log = new MessageBuildingSdkLog();
    final SdkManager manager = AndroidCommonUtils.createSdkManager(sdk.getSdkPath(), log);

    if (manager == null) {
      final String message = log.getErrorMessage();
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 "Android SDK is parsed incorrectly." +
                                                 (message.length() > 0 ? " Parsing log:\n" + message : "")));
      return null;
    }

    final IAndroidTarget target = manager.getTargetFromHashString(targetHashString);
    if (target == null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 "Cannot parse SDK '" + sdk.getName() + "': unknown target " + targetHashString));
      return null;
    }
    return target;
  }

  @Nullable
  public static BuildMessage.Kind toBuildMessageKind(@NotNull AndroidCompilerMessageKind kind) {
    switch (kind) {
      case ERROR:
        return BuildMessage.Kind.ERROR;
      case INFORMATION:
        return BuildMessage.Kind.INFO;
      case WARNING:
        return BuildMessage.Kind.WARNING;
      default:
        LOG.error("unknown AndroidCompilerMessageKind object " + kind);
        return null;
    }
  }

  public static void reportExceptionError(@NotNull CompileContext context,
                                          @Nullable String filePath,
                                          @NotNull Exception exception,
                                          @NotNull String builderName) {
    final String message = exception.getMessage();

    if (message != null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR, message, filePath));
      LOG.debug(exception);
    }
    else {
      context.processMessage(new CompilerMessage(builderName, exception));
    }
  }

  public static boolean containsAndroidFacet(@NotNull ModuleChunk chunk) {
    for (Module module : chunk.getModules()) {
      if (getFacet(module) != null) {
        return true;
      }
    }
    return false;
  }

  public static boolean containsAndroidFacet(@NotNull Project project) {
    for (Module module : project.getModules().values()) {
      if (getFacet(module) != null) {
        return true;
      }
    }
    return false;
  }

  public static ModuleLevelBuilder.ExitCode handleException(@NotNull CompileContext context,
                                                            @NotNull Exception e,
                                                            @NotNull String builderName)
    throws ProjectBuildException {
    String message = e.getMessage();

    if (message == null) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      //noinspection IOResourceOpenedButNotSafelyClosed
      e.printStackTrace(new PrintStream(out));
      message = "Internal error: \n" + out.toString();
    }
    context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR, message));
    throw new ProjectBuildException(message, e);
  }

  @Nullable
  public static File getManifestFileForCompilationPath(@NotNull AndroidFacet facet) throws IOException {
    return facet.getUseCustomManifestForCompilation()
           ? facet.getManifestFileForCompilation()
           : facet.getManifestFile();
  }

  @Nullable
  public static AndroidPlatform getAndroidPlatform(@NotNull Module module,
                                                   @NotNull CompileContext context,
                                                   @NotNull String builderName) {
    final Sdk sdk = module.getSdk();
    if (!(sdk instanceof AndroidSdk)) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 AndroidJpsBundle.message("android.jps.errors.sdk.not.specified", module.getName())));
      return null;
    }
    final AndroidSdk androidSdk = (AndroidSdk)sdk;

    final IAndroidTarget target = parseAndroidTarget(androidSdk, context, builderName);
    if (target == null) {
      context.processMessage(new CompilerMessage(builderName, BuildMessage.Kind.ERROR,
                                                 AndroidJpsBundle.message("android.jps.errors.sdk.invalid", module.getName())));
      return null;
    }
    return new AndroidPlatform(androidSdk, target);
  }

  public static String[] collectResourceDirsForCompilation(@NotNull AndroidFacet facet,
                                                           boolean withCacheDirs,
                                                           @NotNull CompileContext context) throws IOException {
    final List<String> result = new ArrayList<String>();

    if (withCacheDirs) {
      final File resourcesCacheDir = getResourcesCacheDir(context, facet.getModule());
      if (resourcesCacheDir.exists()) {
        result.add(resourcesCacheDir.getPath());
      }
    }

    final File resDir = getResourceDirForCompilationPath(facet);
    if (resDir != null) {
      result.add(resDir.getPath());
    }

    final File generatedResourcesStorage = getGeneratedResourcesStorage(facet.getModule());
    if (generatedResourcesStorage.exists()) {
      result.add(generatedResourcesStorage.getPath());
    }

    for (AndroidFacet depFacet : getAllAndroidDependencies(facet.getModule(), true)) {
      final File depResDir = getResourceDirForCompilationPath(depFacet);
      if (depResDir != null) {
        result.add(depResDir.getPath());
      }
    }
    return ArrayUtil.toStringArray(result);
  }

  @Nullable
  public static File getResourceDirForCompilationPath(@NotNull AndroidFacet facet) throws IOException {
    return facet.getUseCustomResFolderForCompilation()
           ? facet.getResourceDirForCompilation()
           : facet.getResourceDir();
  }

  @NotNull
  static List<AndroidFacet> getAllAndroidDependencies(@NotNull Module module, boolean librariesOnly) {
    final List<AndroidFacet> result = new ArrayList<AndroidFacet>();
    collectDependentAndroidLibraries(module, result, new HashSet<String>(), librariesOnly);
    return result;
  }

  private static void collectDependentAndroidLibraries(@NotNull Module module,
                                                       @NotNull List<AndroidFacet> result,
                                                       @NotNull Set<String> visitedSet,
                                                       boolean librariesOnly) {
    for (ClasspathItem item : module.getClasspath(ClasspathKind.PRODUCTION_RUNTIME, false)) {
      if (item instanceof Module) {
        final Module depModule = (Module)item;
        final AndroidFacet depFacet = getFacet(depModule);

        if (depFacet != null && (!librariesOnly || depFacet.getLibrary()) && visitedSet.add(depModule.getName())) {
          collectDependentAndroidLibraries(depModule, result, visitedSet, librariesOnly);
          result.add(0, depFacet);
        }
      }
    }
  }

  public static boolean isLightBuild(@NotNull CompileContext context) {
    final String typeId = context.getBuilderParameter("RUN_CONFIGURATION_TYPE_ID");
    return typeId != null && AndroidCommonUtils.isTestConfiguration(typeId);
  }

  public static boolean isReleaseBuild(@NotNull CompileContext context) {
    return Boolean.parseBoolean(context.getBuilderParameter(AndroidCommonUtils.RELEASE_BUILD_OPTION));
  }

  @NotNull
  public static File getResourcesCacheDir(@NotNull CompileContext context, @NotNull Module module) {
    final File androidStorage = new File(context.getProjectDescriptor().dataManager.getDataStorageRoot(), ANDROID_STORAGE_DIR);
    return new File(new File(androidStorage, RESOURCE_CACHE_STORAGE), module.getName());
  }

  private static void fillSourceRoots(@NotNull Module module, @NotNull Set<Module> visited, @NotNull Set<File> result)
    throws IOException {
    visited.add(module);
    final AndroidFacet facet = getFacet(module);
    File resDir = null;
    File resDirForCompilation = null;

    if (facet != null) {
      resDir = facet.getResourceDir();
      resDirForCompilation = facet.getResourceDirForCompilation();
    }

    for (String sourceRootPath : module.getSourceRoots()) {
      final File sourceRoot = new File(sourceRootPath).getCanonicalFile();

      if (!sourceRoot.equals(resDir) && !sourceRoot.equals(resDirForCompilation)) {
        result.add(sourceRoot);
      }
    }

    if (facet != null && facet.isPackTestCode()) {
      for (String testRootPath : module.getTestRoots()) {
        final File testRoot = new File(testRootPath).getCanonicalFile();

        if (!testRoot.equals(resDir) && !testRoot.equals(resDirForCompilation)) {
          result.add(testRoot);
        }
      }
    }

    for (ClasspathItem classpathItem : module.getClasspath(ClasspathKind.PRODUCTION_RUNTIME)) {
      if (classpathItem instanceof Module) {
        final Module depModule = (Module)classpathItem;

        if (!visited.contains(depModule)) {
          fillSourceRoots(depModule, visited, result);
        }
      }
    }
  }

  @NotNull
  public static File[] getSourceRootsForModuleAndDependencies(@NotNull Module module) throws IOException {
    Set<File> result = new HashSet<File>();
    fillSourceRoots(module, new HashSet<Module>(), result);
    return result.toArray(new File[result.size()]);
  }

  @Nullable
  public static String getApkPath(@NotNull AndroidFacet facet, @NotNull File outputDirForPackagedArtifacts) {
    final String apkRelativePath = facet.getApkRelativePath();
    final Module module = facet.getModule();

    if (apkRelativePath == null || apkRelativePath.length() == 0) {
      return new File(outputDirForPackagedArtifacts, getApkName(module)).getPath();
    }
    final String moduleDirPath = module.getBasePath();

    return moduleDirPath != null
           ? FileUtil.toSystemDependentName(moduleDirPath + apkRelativePath)
           : null;
  }

  @NotNull
  public static String getApkName(@NotNull Module module) {
    return module.getName() + ".apk";
  }

  @NotNull
  public static File getGeneratedSourcesStorage(@NotNull Module module) {
    final File dataStorageRoot = Utils.getDataStorageRoot(module.getProject());
    final File androidStorageRoot = new File(dataStorageRoot, ANDROID_STORAGE_DIR);
    final File generatedSourcesRoot = new File(androidStorageRoot, GENERATED_SOURCES_FOLDER_NAME);
    return new File(generatedSourcesRoot, module.getName());
  }

  @NotNull
  public static File getGeneratedResourcesStorage(@NotNull Module module) {
    final File dataStorageRoot = Utils.getDataStorageRoot(module.getProject());
    final File androidStorageRoot = new File(dataStorageRoot, ANDROID_STORAGE_DIR);
    final File generatedSourcesRoot = new File(androidStorageRoot, GENERATED_RESOURCES_DIR_NAME);
    return new File(generatedSourcesRoot, module.getName());
  }

  @NotNull
  public static File getStorageFile(@NotNull File dataStorageRoot, @NotNull String storageName) {
    return new File(new File(new File(dataStorageRoot, ANDROID_STORAGE_DIR), storageName), storageName);
  }

  @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
  @Nullable
  private static Properties readPropertyFile(@NotNull File file) {
    final Properties properties = new Properties();
    try {
      properties.load(new FileInputStream(file));
      return properties;
    }
    catch (IOException e) {
      LOG.info(e);
    }
    return null;
  }

  @Nullable
  public static Pair<String, File> getProjectPropertyValue(@NotNull AndroidFacet facet, @NotNull String propertyKey) {
    final File root;
    try {
      root = getMainContentRoot(facet);
    }
    catch (IOException e) {
      return null;
    }
    if (root == null) {
      return null;
    }
    final File projectProperties = new File(root, SdkConstants.FN_PROJECT_PROPERTIES);

    if (projectProperties.exists()) {
      final Properties properties = readPropertyFile(projectProperties);

      if (properties != null) {
        final String value = properties.getProperty(propertyKey);

        if (value != null) {
          return Pair.create(value, projectProperties);
        }
      }
    }
    return null;
  }
}
