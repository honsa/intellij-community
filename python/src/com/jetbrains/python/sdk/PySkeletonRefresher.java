package com.jetbrains.python.sdk;

import com.google.common.collect.Lists;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.SmartList;
import com.intellij.util.io.ZipUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.resolve.PythonSdkPathCache;
import com.jetbrains.python.remote.PythonRemoteInterpreterManager;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jetbrains.python.sdk.SkeletonVersionChecker.fromVersionString;

/**
 * Handles a refresh of SDK's skeletons.
 * Does all the heavy lifting calling skeleton generator, managing blacklists, etc.
 * One-time, non-reusable instances.
 * <br/>
 * User: dcheryasov
 * Date: 4/15/11 5:38 PM
 */
public class PySkeletonRefresher {
  private static final Logger LOG = Logger.getInstance("#" + PySkeletonRefresher.class.getName());


  @Nullable private Project myProject;
  private @Nullable final ProgressIndicator myIndicator;
  private final Sdk mySdk;
  private String mySkeletonsPath;

  @NonNls public static final String BLACKLIST_FILE_NAME = ".blacklist";
  private final static Pattern BLACKLIST_LINE = Pattern.compile("^([^=]+) = (\\d+\\.\\d+) (\\d+)\\s*$");
  // we use the equals sign after filename so that we can freely include space in the filename

  // Path (the first component) may contain spaces, this header spec is deprecated
  private static final Pattern VERSION_LINE_V1 = Pattern.compile("# from (\\S+) by generator (\\S+)\\s*");

  // Skeleton header spec v2
  private static final Pattern FROM_LINE_V2 = Pattern.compile("# from (.*)$");
  private static final Pattern BY_LINE_V2 = Pattern.compile("# by generator (.*)$");

  private String myExtraSyspath;
  private VirtualFile myPregeneratedSkeletons;
  private int myGeneratorVersion;
  private Map<String, Pair<Integer, Long>> myBlacklist;
  private SkeletonVersionChecker myVersionChecker;

  private PySkeletonGenerator mySkeletonsGenerator;

  /**
   * Creates a new object that refreshes skeletons of given SDK.
   *
   * @param sdk           a Python SDK
   * @param skeletonsPath if known; null means 'determine and create as needed'.
   * @param indicator     to report progress of long operations
   */
  public PySkeletonRefresher(@Nullable Project project,
                             @NotNull Sdk sdk,
                             @Nullable String skeletonsPath,
                             @Nullable ProgressIndicator indicator)
    throws InvalidSdkException {
    myProject = project;
    myIndicator = indicator;
    mySdk = sdk;
    mySkeletonsPath = skeletonsPath;
    if (PySdkUtil.isRemote(sdk) && PythonRemoteInterpreterManager.getInstance() != null) {
      //noinspection ConstantConditions
      mySkeletonsGenerator = PythonRemoteInterpreterManager.getInstance().createRemoteSkeletonGenerator(myProject, getSkeletonsPath(), sdk);
    }
    else {
      mySkeletonsGenerator = new PySkeletonGenerator(getSkeletonsPath());
    }
  }

  private void indicate(String msg) {
    if (myIndicator != null) {
      myIndicator.checkCanceled();
      myIndicator.setText(msg);
      myIndicator.setText2("");
    }
  }

  private void indicateMinor(String msg) {
    if (myIndicator != null) {
      myIndicator.setText2(msg);
    }
  }

  private void checkCanceled() {
    if (myIndicator != null) {
      myIndicator.checkCanceled();
    }
  }

  private static String calculateExtraSysPath(@NotNull Sdk sdk, @Nullable String skeletonsPath) {
    final VirtualFile[] classDirs = sdk.getRootProvider().getFiles(OrderRootType.CLASSES);
    final StringBuilder builder = new StringBuilder("\"");
    int i = 0;
    while (i < classDirs.length) {
      if (i > 0) {
        builder.append(File.pathSeparator);
      }
      if (classDirs[i].isInLocalFileSystem()) {
        final String pathname = classDirs[i].getPath();
        if (pathname != null && !pathname.equals(skeletonsPath)) {
          builder.append(pathname);
        }
      }
      i += 1;
    }
    builder.append("\"");
    return builder.toString();
  }

  /**
   * Creates if needed all path(s) used to store skeletons of its SDK.
   *
   * @return path name of skeleton dir for the SDK, guaranteed to be already created.
   */
  @NotNull
  public String getSkeletonsPath() throws InvalidSdkException {
    if (mySkeletonsPath == null) {
      mySkeletonsPath = PythonSdkType.getSkeletonsPath(PathManager.getSystemPath(), mySdk.getHomePath());
      final File skeletonsDir = new File(mySkeletonsPath);
      if (!skeletonsDir.exists() && !skeletonsDir.mkdirs()) {
        throw new InvalidSdkException("Can't create skeleton dir " + String.valueOf(mySkeletonsPath));
      }
    }
    return mySkeletonsPath;
  }

  List<String> regenerateSkeletons(@Nullable SkeletonVersionChecker cachedChecker,
                                   @Nullable Ref<Boolean> migrationFlag) throws InvalidSdkException {
    final List<String> errorList = new SmartList<String>();
    final String homePath = mySdk.getHomePath();
    final String skeletonsPath = getSkeletonsPath();
    final File skeletonsDir = new File(skeletonsPath);
    if (!skeletonsDir.exists()) {
      skeletonsDir.mkdirs();
    }
    final String readablePath = FileUtil.getLocationRelativeToUserHome(homePath);

    myBlacklist = loadBlacklist();

    indicate(PyBundle.message("sdk.gen.querying.$0", readablePath));
    // get generator version and binary libs list in one go

    final PySkeletonGenerator.ListBinariesResult binaries =
      mySkeletonsGenerator.listBinaries(mySdk, calculateExtraSysPath(mySdk, getSkeletonsPath()));
    myGeneratorVersion = binaries.generatorVersion;
    myPregeneratedSkeletons = findPregeneratedSkeletons();

    indicate(PyBundle.message("sdk.gen.reading.versions.file"));
    if (cachedChecker != null) {
      myVersionChecker = cachedChecker.withDefaultVersionIfUnknown(myGeneratorVersion);
    }
    else {
      myVersionChecker = new SkeletonVersionChecker(myGeneratorVersion);
    }

    // check builtins
    final String builtinsFileName = PythonSdkType.getBuiltinsFileName(mySdk);
    final File builtinsFile = new File(skeletonsPath, builtinsFileName);

    final SkeletonHeader oldHeader = readSkeletonHeader(builtinsFile);
    final boolean oldOrNonExisting = oldHeader == null || oldHeader.getVersion() == 0;

    if (migrationFlag != null && !migrationFlag.get() && oldOrNonExisting) {
      migrationFlag.set(true);
      Notifications.Bus.notify(
        new Notification(
          PythonSdkType.SKELETONS_TOPIC, PyBundle.message("sdk.gen.notify.converting.old.skels"),
          PyBundle.message("sdk.gen.notify.converting.text"),
          NotificationType.INFORMATION
        )
      );
    }

    if (myPregeneratedSkeletons != null && oldOrNonExisting) {
      indicate("Unpacking pregenerated skeletons...");
      try {
        final VirtualFile jar = JarFileSystem.getInstance().getVirtualFileForJar(myPregeneratedSkeletons);
        if (jar != null) {
          ZipUtil.extract(new File(jar.getPath()),
                          new File(getSkeletonsPath()), null);
        }
      }
      catch (IOException e) {
        LOG.info("Error unpacking pregenerated skeletons", e);
      }
    }

    if (oldOrNonExisting) {
      final Sdk base = PythonSdkType.getInstance().getVirtualEnvBaseSdk(mySdk);
      if (base != null) {
        indicate("Copying base SDK skeletons for virtualenv...");
        final String baseSkeletonsPath = PythonSdkType.getSkeletonsPath(PathManager.getSystemPath(), base.getHomePath());
        final PySkeletonGenerator.ListBinariesResult baseBinaries =
          mySkeletonsGenerator.listBinaries(base, calculateExtraSysPath(base, baseSkeletonsPath));
        for (Map.Entry<String, PyBinaryItem> entry : binaries.modules.entrySet()) {
          final String module = entry.getKey();
          final PyBinaryItem binary = entry.getValue();
          final PyBinaryItem baseBinary = baseBinaries.modules.get(module);
          final File fromFile = getSkeleton(module, baseSkeletonsPath);
          if (baseBinaries.modules.containsKey(module) &&
              fromFile.exists() &&
              binary.length() == baseBinary.length()) { // Weak binary modules equality check
            final File toFile = fromFile.isDirectory() ?
                                getPackageSkeleton(module, skeletonsPath) :
                                getModuleSkeleton(module, skeletonsPath);
            try {
              FileUtil.copy(fromFile, toFile);
            }
            catch (IOException e) {
              LOG.info("Error copying base virtualenv SDK skeleton for " + module, e);
            }
          }
        }
      }
    }

    final SkeletonHeader newHeader = readSkeletonHeader(builtinsFile);
    final boolean mustUpdateBuiltins = myPregeneratedSkeletons == null &&
                                       (newHeader == null || newHeader.getVersion() < myVersionChecker.getBuiltinVersion());
    if (mustUpdateBuiltins) {
      indicate(PyBundle.message("sdk.gen.updating.builtins.$0", readablePath));
      if (mySdk != null) {
        mySkeletonsGenerator.generateBuiltinSkeletons(mySdk);
        if (myProject != null) {
          PythonSdkPathCache.getInstance(myProject, mySdk).clearBuiltins();
        }
      }
    }

    if (!binaries.modules.isEmpty()) {

      indicate(PyBundle.message("sdk.gen.updating.$0", readablePath));

      List<UpdateResult> updateErrors = updateOrCreateSkeletons(binaries.modules); //Skeletons regeneration

      if (updateErrors.size() > 0) {
        indicateMinor(BLACKLIST_FILE_NAME);
        for (UpdateResult error : updateErrors) {
          if (error.isFresh()) errorList.add(error.getName());
          myBlacklist.put(error.getPath(), new Pair<Integer, Long>(myGeneratorVersion, error.getTimestamp()));
        }
        storeBlacklist(skeletonsDir, myBlacklist);
      }
      else {
        removeBlacklist(skeletonsDir);
      }
    }

    indicate(PyBundle.message("sdk.gen.reloading"));

    mySkeletonsGenerator.refreshGeneratedSkeletons();

    if (!oldOrNonExisting) {
      indicate(PyBundle.message("sdk.gen.cleaning.$0", readablePath));
      cleanUpSkeletons(skeletonsDir);
    }

    if (mustUpdateBuiltins) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          DaemonCodeAnalyzer.getInstance(myProject).restart();
        }
      });
    }

    return errorList;
  }

  @Nullable
  private static SkeletonHeader readSkeletonHeader(@NotNull File file) {
    try {
      final LineNumberReader reader = new LineNumberReader(new FileReader(file));
      try {
        String line = null;
        // Read 3 lines, skip first 2: encoding, module name
        for (int i = 0; i < 3; i++) {
          line = reader.readLine();
          if (line == null) {
            return null;
          }
        }
        // Try the old whitespace-unsafe header format v1 first
        final Matcher v1Matcher = VERSION_LINE_V1.matcher(line);
        if (v1Matcher.matches()) {
          return new SkeletonHeader(v1Matcher.group(1), fromVersionString(v1Matcher.group(2)));
        }
        final Matcher fromMatcher = FROM_LINE_V2.matcher(line);
        if (fromMatcher.matches()) {
          final String binaryFile = fromMatcher.group(1);
          line = reader.readLine();
          if (line != null) {
            final Matcher byMatcher = BY_LINE_V2.matcher(line);
            if (byMatcher.matches()) {
              final int version = fromVersionString(byMatcher.group(1));
              return new SkeletonHeader(binaryFile, version);
            }
          }
        }
      }
      finally {
        reader.close();
      }
    }
    catch (IOException e) {
    }
    return null;
  }

  static class SkeletonHeader {
    @NotNull private final String myFile;
    private final int myVersion;

    public SkeletonHeader(@NotNull String binaryFile, int version) {
      myFile = binaryFile;
      myVersion = version;
    }

    @NotNull
    public String getBinaryFile() {
      return myFile;
    }

    public int getVersion() {
      return myVersion;
    }
  }

  private Map<String, Pair<Integer, Long>> loadBlacklist() {
    Map<String, Pair<Integer, Long>> ret = new HashMap<String, Pair<Integer, Long>>();
    File blacklistFile = new File(mySkeletonsPath, BLACKLIST_FILE_NAME);
    if (blacklistFile.exists() && blacklistFile.canRead()) {
      Reader input;
      try {
        input = new FileReader(blacklistFile);
        LineNumberReader lines = new LineNumberReader(input);
        try {
          String line;
          do {
            line = lines.readLine();
            if (line != null && line.length() > 0 && line.charAt(0) != '#') { // '#' begins a comment
              Matcher matcher = BLACKLIST_LINE.matcher(line);
              boolean notParsed = true;
              if (matcher.matches()) {
                final int version = fromVersionString(matcher.group(2));
                if (version > 0) {
                  try {
                    final long timestamp = Long.parseLong(matcher.group(3));
                    final String filename = matcher.group(1);
                    ret.put(filename, new Pair<Integer, Long>(version, timestamp));
                    notParsed = false;
                  }
                  catch (NumberFormatException ignore) {
                  }
                }
              }
              if (notParsed) LOG.warn("In blacklist at " + mySkeletonsPath + " strange line '" + line + "'");
            }
          }
          while (line != null);
        }
        catch (IOException ex) {
          LOG.warn("Failed to read blacklist in " + mySkeletonsPath, ex);
        }
        finally {
          lines.close();
        }
      }
      catch (IOException ignore) {
      }
    }
    return ret;
  }

  private static void storeBlacklist(File skeletonDir, Map<String, Pair<Integer, Long>> blacklist) {
    File blacklistFile = new File(skeletonDir, BLACKLIST_FILE_NAME);
    PrintWriter output;
    try {
      output = new PrintWriter(blacklistFile);
      try {
        output.println("# PyCharm failed to generate skeletons for these modules.");
        output.println("# These skeletons will be re-generated automatically");
        output.println("# when a newer module version or an updated generator becomes available.");
        // each line:   filename = version.string timestamp
        for (String fname : blacklist.keySet()) {
          Pair<Integer, Long> data = blacklist.get(fname);
          output.print(fname);
          output.print(" = ");
          output.print(SkeletonVersionChecker.toVersionString(data.getFirst()));
          output.print(" ");
          output.print(data.getSecond());
          output.println();
        }
      }
      finally {
        output.close();
      }
    }
    catch (IOException ex) {
      LOG.warn("Failed to store blacklist in " + skeletonDir.getPath(), ex);
    }
  }

  private static void removeBlacklist(File skeletonDir) {
    File blacklistFile = new File(skeletonDir, BLACKLIST_FILE_NAME);
    if (blacklistFile.exists()) {
      boolean okay = blacklistFile.delete();
      if (!okay) LOG.warn("Could not delete blacklist file in " + skeletonDir.getPath());
    }
  }

  /**
   * For every existing skeleton file, take its module file name,
   * and remove the skeleton if the module file does not exist.
   * Works recursively starting from dir. Removes dirs that become empty.
   */
  private void cleanUpSkeletons(final File dir) {
    indicateMinor(dir.getPath());
    final File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File item : files) {
      if (item.isDirectory()) {
        cleanUpSkeletons(item);
        // was the dir emptied?
        File[] remaining = item.listFiles();
        if (remaining != null && remaining.length == 0) {
          mySkeletonsGenerator.deleteOrLog(item);
        }
        else if (remaining != null && remaining.length == 1) { //clean also if contains only __init__.py
          File lastFile = remaining[0];
          if (PyNames.INIT_DOT_PY.equals(lastFile.getName()) && lastFile.length() == 0) {
            boolean deleted = mySkeletonsGenerator.deleteOrLog(lastFile);
            if (deleted) mySkeletonsGenerator.deleteOrLog(item);
          }
        }
      }
      else if (item.isFile()) {
        // clean up an individual file
        final String itemName = item.getName();
        if (PyNames.INIT_DOT_PY.equals(itemName) && item.length() == 0) continue; // these are versionless
        if (BLACKLIST_FILE_NAME.equals(itemName)) continue; // don't touch the blacklist
        final SkeletonHeader header = readSkeletonHeader(item);
        boolean canLive = header != null;
        if (canLive) {
          final String binaryFile = header.getBinaryFile();
          canLive = SkeletonVersionChecker.BUILTIN_NAME.equals(binaryFile) || mySkeletonsGenerator.exists(binaryFile);
        }
        if (!canLive) {
          mySkeletonsGenerator.deleteOrLog(item);
        }
      }
    }
  }

  private static class UpdateResult {
    private final String myPath;
    private final String myName;
    private final long myTimestamp;

    public boolean isFresh() {
      return myIsFresh;
    }

    private final boolean myIsFresh;

    private UpdateResult(String name, String path, long timestamp, boolean fresh) {
      myName = name;
      myPath = path;
      myTimestamp = timestamp;
      myIsFresh = fresh;
    }

    public String getName() {
      return myName;
    }

    public String getPath() {
      return myPath;
    }

    public Long getTimestamp() {
      return myTimestamp;
    }
  }

  /**
   * (Re-)generates skeletons for all binary python modules. Up-to-date skeletons are not regenerated.
   * Does one module at a time: slower, but avoids certain conflicts.
   *
   * @param modules output of generator3 -L
   * @return blacklist data; whatever was not generated successfully is put here.
   */
  private List<UpdateResult> updateOrCreateSkeletons(Map<String, PyBinaryItem> modules) throws InvalidSdkException {
    long startTime = System.currentTimeMillis();

    final List<String> names = Lists.newArrayList(modules.keySet());
    Collections.sort(names);
    final List<UpdateResult> results = new ArrayList<UpdateResult>();
    final int count = names.size();
    for (int i = 0; i < count; i++) {
      checkCanceled();
      if (myIndicator != null) {
        myIndicator.setFraction((double)i / count);
      }
      final String name = names.get(i);
      final PyBinaryItem module = modules.get(name);
      if (module != null) {
        updateOrCreateSkeleton(module, results);
      }
    }
    finishSkeletonsGeneration();


    long doneInMs = System.currentTimeMillis() - startTime;

    LOG.info("Rebuilding skeletons for binaries took " + doneInMs + " ms");

    return results;
  }

  private void finishSkeletonsGeneration() {
    mySkeletonsGenerator.finishSkeletonsGeneration();
  }

  private static File getSkeleton(String moduleName, String skeletonsPath) {
    final File module = getModuleSkeleton(moduleName, skeletonsPath);
    return module.exists() ? module : getPackageSkeleton(moduleName, skeletonsPath);
  }

  private static File getModuleSkeleton(String module, String skeletonsPath) {
    final String modulePath = module.replace('.', '/');
    return new File(skeletonsPath, modulePath + ".py");
  }

  private static File getPackageSkeleton(String pkg, String skeletonsPath) {
    final String packagePath = pkg.replace('.', '/');
    return new File(new File(skeletonsPath, packagePath), PyNames.INIT_DOT_PY);
  }

  private boolean updateOrCreateSkeleton(final PyBinaryItem binaryItem,
                                         final List<UpdateResult> errorList) throws InvalidSdkException {
    final String moduleName = binaryItem.getModule();

    final File skeleton = getSkeleton(moduleName, getSkeletonsPath());
    final SkeletonHeader header = readSkeletonHeader(skeleton);
    boolean mustRebuild = true; // guilty unless proven fresh enough
    if (header != null) {
      int requiredVersion = myVersionChecker.getRequiredVersion(moduleName);
      mustRebuild = header.getVersion() < requiredVersion;
    }
    if (!mustRebuild) { // ...but what if the lib was updated?
      mustRebuild = (skeleton.exists() && binaryItem.lastModified() > skeleton.lastModified());
      // really we can omit both exists() calls but I keep these to make the logic clear
    }
    if (myBlacklist != null) {
      Pair<Integer, Long> versionInfo = myBlacklist.get(binaryItem.getPath());
      if (versionInfo != null) {
        int failedGeneratorVersion = versionInfo.getFirst();
        long failedTimestamp = versionInfo.getSecond();
        mustRebuild &= failedGeneratorVersion < myGeneratorVersion || failedTimestamp < binaryItem.lastModified();
        if (!mustRebuild) { // we're still failing to rebuild, it, keep it in blacklist
          errorList.add(new UpdateResult(moduleName, binaryItem.getPath(), binaryItem.lastModified(), false));
        }
      }
    }
    if (mustRebuild) {
      indicateMinor(moduleName);
      if (myPregeneratedSkeletons != null && copyPregeneratedSkeleton(moduleName)) {
        return true;
      }
      LOG.info("Skeleton for " + moduleName);

      generateSkeleton(moduleName, binaryItem.getPath(), null, new Consumer<Boolean>() {
        @Override
        public void consume(Boolean generated) {
          if (!generated) {
            errorList.add(new UpdateResult(moduleName, binaryItem.getPath(), binaryItem.lastModified(), true));
          }
        }
      });
    }
    return false;
  }

  public static class PyBinaryItem {
    private String myPath;
    private String myModule;
    private long myLength;
    private long myLastModified;

    PyBinaryItem(String module, String path, long length, long lastModified) {
      myPath = path;
      myModule = module;
      myLength = length;
      myLastModified = lastModified * 1000;
    }

    public String getPath() {
      return myPath;
    }

    public String getModule() {
      return myModule;
    }

    public long length() {
      return myLength;
    }

    public long lastModified() {
      return myLastModified;
    }
  }

  private boolean copyPregeneratedSkeleton(String moduleName) throws InvalidSdkException {
    File targetDir;
    final String modulePath = moduleName.replace('.', '/');
    File skeletonsDir = new File(getSkeletonsPath());
    VirtualFile pregenerated = myPregeneratedSkeletons.findFileByRelativePath(modulePath + ".py");
    if (pregenerated == null) {
      pregenerated = myPregeneratedSkeletons.findFileByRelativePath(modulePath + "/" + PyNames.INIT_DOT_PY);
      targetDir = new File(skeletonsDir, modulePath);
    }
    else {
      int pos = modulePath.lastIndexOf('/');
      if (pos < 0) {
        targetDir = skeletonsDir;
      }
      else {
        final String moduleParentPath = modulePath.substring(0, pos);
        targetDir = new File(skeletonsDir, moduleParentPath);
      }
    }
    if (pregenerated != null && (targetDir.exists() || targetDir.mkdirs())) {
      LOG.info("Pregenerated skeleton for " + moduleName);
      File target = new File(targetDir, pregenerated.getName());
      try {
        FileOutputStream fos = new FileOutputStream(target);
        try {
          FileUtil.copy(pregenerated.getInputStream(), fos);
        }
        finally {
          fos.close();
        }
      }
      catch (IOException e) {
        LOG.info("Error copying pregenerated skeleton", e);
        return false;
      }
      return true;
    }
    return false;
  }

  @Nullable
  private VirtualFile findPregeneratedSkeletons() {
    final File root = findPregeneratedSkeletonsRoot();
    if (root == null) {
      return null;
    }
    LOG.info("Pregenerated skeletons root is " + root);
    final String versionString = mySdk.getVersionString();
    if (versionString == null) {
      return null;
    }

    if (PySdkUtil.isRemote(mySdk)) {
      return null;
    }

    String version = versionString.toLowerCase().replace(" ", "-");
    File f;
    if (SystemInfo.isMac) {
      String osVersion = SystemInfo.OS_VERSION;
      int dot = osVersion.indexOf('.');
      if (dot >= 0) {
        int secondDot = osVersion.indexOf('.', dot + 1);
        if (secondDot >= 0) {
          osVersion = osVersion.substring(0, secondDot);
        }
      }
      f = new File(root, "skeletons-mac-" + myGeneratorVersion + "-" + osVersion + "-" + version + ".zip");
    }
    else {
      String os = SystemInfo.isWindows ? "win" : "nix";
      f = new File(root, "skeletons-" + os + "-" + myGeneratorVersion + "-" + version + ".zip");
    }
    if (f.exists()) {
      LOG.info("Found pregenerated skeletons at " + f.getPath());
      final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
      if (virtualFile == null) {
        LOG.info("Could not find pregenerated skeletons in VFS");
        return null;
      }
      return JarFileSystem.getInstance().getJarRootForLocalFile(virtualFile);
    }
    else {
      LOG.info("Not found pregenerated skeletons at " + f.getPath());
      return null;
    }
  }

  @Nullable
  private static File findPregeneratedSkeletonsRoot() {
    final String path = PathManager.getHomePath();
    LOG.info("Home path is " + path);
    File f = new File(path, "python/skeletons");  // from sources
    if (f.exists()) return f;
    f = new File(path, "skeletons");              // compiled binary
    if (f.exists()) return f;
    return null;
  }

  /**
   * Generates a skeleton for a particular binary module.
   *
   * @param modname        name of the binary module as known to Python (e.g. 'foo.bar')
   * @param modfilename    name of file which defines the module, null for built-in modules
   * @param assemblyRefs   refs that generator wants to know in .net environment, if applicable
   * @param resultConsumer accepts true if generation completed successfully
   */
  public void generateSkeleton(@NotNull String modname, @Nullable String modfilename,
                               @Nullable List<String> assemblyRefs, Consumer<Boolean> resultConsumer) throws InvalidSdkException {
    mySkeletonsGenerator.generateSkeleton(modname, modfilename, assemblyRefs, getExtraSyspath(), mySdk.getHomePath(), resultConsumer);
  }


  private String getExtraSyspath() {
    if (myExtraSyspath == null) {
      myExtraSyspath = calculateExtraSysPath(mySdk, mySkeletonsPath);
    }
    return myExtraSyspath;
  }
}
