/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * *************
 *
 * <p>This code can be embedded in arbitrary third-party projects! For maximum compatibility, use
 * only Java 7 constructs.
 *
 * <p>*************
 */
package com.facebook.buck.jvm.java;

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FatJarMain {

  private FatJarMain() {}

  /**
   * Main method for fat jar. Unpacks artifact and native libraries into a temporary folder and then
   * launches original artifact as java process.
   */
  @SuppressWarnings("PMD.BlacklistedDefaultProcessMethod")
  public static void main(String[] args) throws Exception {
    ClassLoader classLoader = FatJarMain.class.getClassLoader();

    // Load the fat jar info from it's resource.
    FatJar fatJar = FatJar.load(classLoader);

    // Create a temp dir to house the native libraries.
    try (ManagedTemporaryDirectory temp = new ManagedTemporaryDirectory("fatjar.")) {

      // Unpack the real, inner artifact (JAR or wrapper script).
      boolean isWrapperScript = fatJar.isWrapperScript();
      Path innerArtifact = temp.getPath().resolve(isWrapperScript ? "wrapper.sh" : "main.jar");
      fatJar.unpackInnerArtifactTo(classLoader, innerArtifact);
      if (isWrapperScript) {
        makeExecutable(innerArtifact);
      }

      // Unpack all the native libraries, since the system loader will need to find these on disk.
      Path nativeLibs = temp.getPath().resolve("native_libs");
      Files.createDirectory(nativeLibs);
      fatJar.unpackNativeLibrariesInto(classLoader, temp.getPath());

      // Update the appropriate environment variable with the location of our native libraries
      // and start the real main class in a new process so that it picks it up.
      ProcessBuilder builder = new ProcessBuilder();
      builder.command(getCommand(isWrapperScript, innerArtifact, args));
      updateEnvironment(builder.environment(), temp.getPath());
      builder.inheritIO();

      // Wait for the inner process to finish, and propagate it's exit code, before cleaning
      // up the native libraries.
      System.exit(builder.start().waitFor());
    }
  }

  /**
   * Update the library search path environment variable with the given native library directory.
   */
  private static void updateEnvironment(Map<String, String> env, Path libDir) {
    String librarySearchPathName = getLibrarySearchPathName();
    String originalLibPath = getEnvValue(librarySearchPathName);
    String newLibPath =
        libDir + (originalLibPath == null ? "" : File.pathSeparator + originalLibPath);
    env.put(librarySearchPathName, newLibPath);
  }

  private static List<String> getJVMArguments() {
    RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
    try {
      return runtimeMxBean.getInputArguments();
    } catch (java.lang.SecurityException e) {
      // Do not have the ManagementPermission("monitor") permission
      return Collections.emptyList();
    }
  }

  /** @return a command to start a new JVM process to execute the given main class. */
  private static List<String> getCommand(boolean wrapperScript, Path artifact, String[] args)
      throws IOException {
    List<String> cmd = new ArrayList<>();

    // Look for the Java binary given in an alternate location if given,
    // otherwise use the Java binary that started us
    String javaHome = System.getProperty("buck.fatjar.java.home", System.getProperty("java.home"));
    cmd.add(Paths.get(javaHome, "bin", "java").toString());
    // Pass through any VM arguments to the child process
    cmd.addAll(getJVMArguments());
    // Directs the VM to refrain from setting the file descriptor limit to the default maximum.
    // https://stackoverflow.com/a/16535804/5208808
    cmd.add("-XX:-MaxFDLimit");

    if (wrapperScript) {
      List<String> strings = Files.readAllLines(artifact, StandardCharsets.UTF_8);
      if (strings.size() != 1) {
        throw new IllegalStateException(
            String.format(
                "Expected to read only 1 line from the wrapper script: %s, but read: %s",
                artifact, strings.size()));
      }
      String command = strings.iterator().next();
      String[] wrapperCommand = command.split("\\s+");

      // classpath
      cmd.add(wrapperCommand[1]);
      cmd.add(wrapperCommand[2]);
      if (wrapperCommand.length > 4) {
        // main class
        String mainClass = wrapperCommand[3];
        cmd.add(mainClass);
      }
    } else {
      cmd.add("-jar");
      // Lookup our current JAR context.
      cmd.add(artifact.toString());
    }
    // pass args to new java process
    Collections.addAll(cmd, args);

    /* On Windows, we need to escape the arguments we hand off to `CreateProcess`.  See
     * http://blogs.msdn.com/b/twistylittlepassagesallalike/archive/2011/04/23/everyone-quotes-arguments-the-wrong-way.aspx
     * for more details.
     */
    if (isWindowsOs(getOsPlatform())) {
      List<String> escapedCommand = new ArrayList<>(cmd.size());
      for (String c : cmd) {
        escapedCommand.add(WindowsCreateProcessEscape.quote(c));
      }
      return escapedCommand;
    }

    return cmd;
  }

  private static void makeExecutable(Path file) throws IOException {
    if (!file.toFile().setExecutable(true, true)) {
      throw new IOException("The file could not be made executable");
    }
  }

  /**
   * @return the platform specific environment variable for setting the native library search path.
   */
  private static String getLibrarySearchPathName() {
    String platform = getOsPlatform();
    if (platform.startsWith("Linux")) {
      return "LD_LIBRARY_PATH";
    } else if (platform.startsWith("Mac OS")) {
      return "DYLD_LIBRARY_PATH";
    } else if (isWindowsOs(platform)) {
      return "PATH";
    } else {
      System.err.println(
          "WARNING: using \"LD_LIBRARY_PATH\" for unrecognized platform " + platform);
      return "LD_LIBRARY_PATH";
    }
  }

  @Nullable
  // Avoid using EnvVariablesProvider to avoid extra dependencies.
  @SuppressWarnings("PMD.BlacklistedSystemGetenv")
  private static String getEnvValue(String envVariableName) {
    if (isWindowsOs(getOsPlatform())) {
      return findMapValueIgnoreKeyCase(envVariableName, System.getenv());
    } else {
      return System.getenv(envVariableName);
    }
  }

  @Nullable
  private static String findMapValueIgnoreKeyCase(String key, Map<String, String> map) {
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String getOsPlatform() {
    return Objects.requireNonNull(System.getProperty("os.name"));
  }

  private static boolean isWindowsOs(String osPlatform) {
    return osPlatform.startsWith("Windows");
  }

  /**
   * A temporary directory that automatically cleans itself up when used via a try-resource block.
   */
  private static class ManagedTemporaryDirectory implements AutoCloseable {

    private final Path path;

    private ManagedTemporaryDirectory(Path path) {
      this.path = path;
    }

    public ManagedTemporaryDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
      this(Files.createTempDirectory(prefix, attrs));
    }

    @Override
    public void close() throws IOException {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              if (e != null) {
                throw e;
              }
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }

    public Path getPath() {
      return path;
    }
  }
}
