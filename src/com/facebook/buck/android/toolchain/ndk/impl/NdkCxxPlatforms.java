/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.AndroidNdkConstants;
import com.facebook.buck.android.toolchain.ndk.NdkCompilerType;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformCompiler;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformTargetConfiguration;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntimeType;
import com.facebook.buck.android.toolchain.ndk.NdkTargetArchAbi;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.ArchiverProvider;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.ElfSharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.GnuArchiver;
import com.facebook.buck.cxx.toolchain.HeaderVerification;
import com.facebook.buck.cxx.toolchain.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.toolchain.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.ToolType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.impl.GnuLinker;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.environment.PlatformType;
import com.facebook.infer.annotation.Assertions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class NdkCxxPlatforms {

  private static final Logger LOG = Logger.get(NdkCxxPlatforms.class);

  /**
   * Magic string we substitute into debug paths in place of the build-host name, erasing the
   * difference between say, building on Darwin and building on Linux.
   */
  public static final String BUILD_HOST_SUBST = "@BUILD_HOST@";

  public static final String DEFAULT_TARGET_APP_PLATFORM = "android-16";

  private static final ImmutableMap<Platform, Host> BUILD_PLATFORMS =
      ImmutableMap.of(
          Platform.LINUX, Host.LINUX_X86_64,
          Platform.MACOS, Host.DARWIN_X86_64,
          Platform.WINDOWS, Host.WINDOWS_X86_64);

  // TODO(cjhopman): Does the preprocessor need the -std= flags? Right now we don't send them.
  /** Defaults for c and c++ flags */
  public static final ImmutableList<String> DEFAULT_COMMON_CFLAGS =
      ImmutableList.of(
          // Default to the C11 standard.
          "-std=gnu11");

  public static final ImmutableList<String> DEFAULT_COMMON_CXXFLAGS =
      ImmutableList.of(
          // Default to the C++11 standard.
          "-std=gnu++11", "-fno-exceptions", "-fno-rtti");

  public static final ImmutableList<String> DEFAULT_COMMON_CPPFLAGS =
      ImmutableList.of(
          // Disable searching for headers provided by the system.  This limits headers to just
          // those provided by the NDK and any library dependencies.
          "-nostdinc",
          // Default macro definitions applied to all builds.
          "-DNDEBUG",
          "-DANDROID");

  public static final ImmutableList<String> DEFAULT_COMMON_CXXPPFLAGS = DEFAULT_COMMON_CPPFLAGS;

  /** Flags used when compiling either C or C++ sources. */
  public static final ImmutableList<String> DEFAULT_COMMON_COMPILER_FLAGS =
      ImmutableList.of(
          // Default compiler flags provided by the NDK build makefiles.
          "-ffunction-sections", "-funwind-tables", "-fomit-frame-pointer", "-fno-strict-aliasing");

  /** Default linker flags added by the NDK. */
  public static final ImmutableList<String> DEFAULT_COMMON_LDFLAGS =
      ImmutableList.of(
          // Add a deterministic build ID to Android builds.
          // We use it to find symbols from arbitrary binaries.
          "-Wl,--build-id",
          // Enforce the NX (no execute) security feature
          "-Wl,-z,noexecstack",
          // Strip unused code
          "-Wl,--gc-sections",
          // Refuse to produce dynamic objects with undefined symbols
          "-Wl,-z,defs",
          // Forbid dangerous copy "relocations"
          "-Wl,-z,nocopyreloc",
          // We always pass the runtime library on the command line, so setting this flag
          // means the resulting link will only use it if it was actually needed it.
          "-Wl,--as-needed");

  private static final Pattern NDK_MAJOR_VERSION_PATTERN = Pattern.compile("^[rR]?(\\d+).*");
  private static final Pattern NDK_MINOR_VERSION_PATTERN = Pattern.compile("^[rR]?\\d+\\.(\\d+).*");

  // Utility class, do not instantiate.
  private NdkCxxPlatforms() {}

  static int getNdkMajorVersion(String ndkVersion) {
    return Integer.parseInt(NDK_MAJOR_VERSION_PATTERN.matcher(ndkVersion).replaceAll("$1"));
  }

  static int getNdkMinorVersion(String ndkVersion) {
    String minorVersion = NDK_MINOR_VERSION_PATTERN.matcher(ndkVersion).replaceAll("$1");
    if (minorVersion.startsWith("r") || minorVersion.startsWith("R")) {
      // Failed to find a minor version, 0 is a safe fallback.
      return 0;
    }
    return Integer.parseInt(minorVersion);
  }

  public static NdkCompilerType getDefaultCompilerTypeForNdk(String ndkVersion) {
    return getNdkMajorVersion(ndkVersion) < 18 ? NdkCompilerType.GCC : NdkCompilerType.CLANG;
  }

  public static NdkCxxRuntime getDefaultCxxRuntimeForNdk(String ndkVersion) {
    return getNdkMajorVersion(ndkVersion) < 18 ? NdkCxxRuntime.GNUSTL : NdkCxxRuntime.LIBCXX;
  }

  public static String getDefaultGccVersionForNdk(String ndkVersion) {
    return getNdkMajorVersion(ndkVersion) < 11 ? "4.8" : "4.9";
  }

  public static String getDefaultClangVersionForNdk(String ndkVersion) {
    int ndkMajorVersion = getNdkMajorVersion(ndkVersion);
    int ndkMinorVersion = getNdkMinorVersion(ndkVersion);

    if (ndkMajorVersion < 11) {
      return "3.5";
    } else if (ndkMajorVersion < 15) {
      return "3.8";
    } else if (ndkMajorVersion < 17) {
      return "5.0";
    } else if (ndkMajorVersion < 18) {
      return "6.0.2";
    } else if (ndkMajorVersion < 19) {
      return "7.0.2";
    } else if (ndkMajorVersion < 20) {
      return "8.0.2";
    } else if (ndkMajorVersion < 21) {
      return "8.0.7";
    } else if (ndkMajorVersion == 21 && ndkMinorVersion <= 3) {
      return "9.0.8";
    } else {
      return "9.0.9";
    }
  }

  public static boolean isSupportedConfiguration(Path ndkRoot, NdkCxxRuntime cxxRuntime) {
    // TODO(12846101): With ndk r12, Android has started to use libc++abi. Buck
    // needs to figure out how to support that.
    String ndkVersion = readVersion(ndkRoot);
    return !(cxxRuntime == NdkCxxRuntime.LIBCXX && getNdkMajorVersion(ndkVersion) >= 12);
  }

  /** Gets all the unresolved {@link NdkCxxPlatform} based on the buckconfig. */
  public static ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      TargetConfiguration targetConfiguration,
      Platform platform,
      ToolchainProvider toolchainProvider,
      String ndkVersion) {
    AndroidNdk androidNdk =
        toolchainProvider.getByName(AndroidNdk.DEFAULT_NAME, targetConfiguration, AndroidNdk.class);
    Path ndkRoot = androidNdk.getNdkRootPath();

    NdkCompilerType compilerType =
        androidConfig
            .getNdkCompiler()
            .orElse(NdkCxxPlatforms.getDefaultCompilerTypeForNdk(ndkVersion));
    String gccVersion =
        androidConfig
            .getNdkGccVersion()
            .orElse(NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion));
    String clangVersion =
        androidConfig
            .getNdkClangVersion()
            .orElse(NdkCxxPlatforms.getDefaultClangVersionForNdk(ndkVersion));
    String compilerVersion = compilerType == NdkCompilerType.GCC ? gccVersion : clangVersion;
    NdkCxxPlatformCompiler compiler =
        NdkCxxPlatformCompiler.of(compilerType, compilerVersion, gccVersion);
    return getPlatforms(
        config,
        androidConfig,
        filesystem,
        ndkRoot,
        compiler,
        androidConfig.getNdkCxxRuntime().orElseGet(() -> getDefaultCxxRuntimeForNdk(ndkVersion)),
        androidConfig.getNdkCxxRuntimeType().orElse(NdkCxxRuntimeType.DYNAMIC),
        androidConfig.getNdkCpuAbis().orElseGet(() -> getDefaultCpuAbis(ndkVersion)),
        platform);
  }

  @VisibleForTesting
  static ImmutableSet<NdkTargetArchAbi> getDefaultCpuAbis(String ndkVersion) {
    int ndkMajorVersion = getNdkMajorVersion(ndkVersion);
    if (ndkMajorVersion > 16) {
      return ImmutableSet.of(NdkTargetArchAbi.ARMEABI_V7A, NdkTargetArchAbi.X86);
    } else {
      return ImmutableSet.of(
          NdkTargetArchAbi.ARMEABI, NdkTargetArchAbi.ARMEABI_V7A, NdkTargetArchAbi.X86);
    }
  }

  @VisibleForTesting
  public static ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      NdkCxxRuntime cxxRuntime,
      NdkCxxRuntimeType runtimeType,
      Set<NdkTargetArchAbi> cpuAbis,
      Platform platform) {
    return getPlatforms(
        config,
        androidConfig,
        filesystem,
        ndkRoot,
        compiler,
        cxxRuntime,
        runtimeType,
        cpuAbis,
        platform,
        new ExecutableFinder(),
        /* strictToolchainPaths */ true);
  }

  /** @return the map holding the available {@link NdkCxxPlatform}s. */
  public static ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> getPlatforms(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      NdkCxxRuntime cxxRuntime,
      NdkCxxRuntimeType runtimeType,
      Set<NdkTargetArchAbi> cpuAbis,
      Platform platform,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths) {
    ImmutableMap.Builder<TargetCpuType, UnresolvedNdkCxxPlatform> ndkCxxPlatformBuilder =
        ImmutableMap.builder();

    // ARM Platform
    if (cpuAbis.contains(NdkTargetArchAbi.ARMEABI)) {
      ndkCxxPlatformBuilder.put(
          TargetCpuType.ARM,
          getNdkCxxPlatform(
              config,
              androidConfig,
              filesystem,
              ndkRoot,
              compiler,
              cxxRuntime,
              runtimeType,
              platform,
              executableFinder,
              strictToolchainPaths,
              "arm",
              TargetCpuType.ARM,
              "android-arm"));
    }

    // ARMv7 Platform
    if (cpuAbis.contains(NdkTargetArchAbi.ARMEABI_V7A)) {
      ndkCxxPlatformBuilder.put(
          TargetCpuType.ARMV7,
          getNdkCxxPlatform(
              config,
              androidConfig,
              filesystem,
              ndkRoot,
              compiler,
              cxxRuntime,
              runtimeType,
              platform,
              executableFinder,
              strictToolchainPaths,
              "armv7",
              TargetCpuType.ARMV7,
              "android-armv7"));
    }

    // ARM64 Platform
    if (cpuAbis.contains(NdkTargetArchAbi.ARM64_V8A)) {
      ndkCxxPlatformBuilder.put(
          TargetCpuType.ARM64,
          getNdkCxxPlatform(
              config,
              androidConfig,
              filesystem,
              ndkRoot,
              compiler,
              cxxRuntime,
              runtimeType,
              platform,
              executableFinder,
              strictToolchainPaths,
              "arm64",
              TargetCpuType.ARM64,
              "android-arm64"));
    }

    // x86 Platform
    if (cpuAbis.contains(NdkTargetArchAbi.X86)) {
      ndkCxxPlatformBuilder.put(
          TargetCpuType.X86,
          getNdkCxxPlatform(
              config,
              androidConfig,
              filesystem,
              ndkRoot,
              compiler,
              cxxRuntime,
              runtimeType,
              platform,
              executableFinder,
              strictToolchainPaths,
              "x86",
              TargetCpuType.X86,
              "android-x86"));
    }

    // x86_64 Platform
    if (cpuAbis.contains(NdkTargetArchAbi.X86_64)) {
      ndkCxxPlatformBuilder.put(
          TargetCpuType.X86_64,
          getNdkCxxPlatform(
              config,
              androidConfig,
              filesystem,
              ndkRoot,
              compiler,
              cxxRuntime,
              runtimeType,
              platform,
              executableFinder,
              strictToolchainPaths,
              "x86_64",
              TargetCpuType.X86_64,
              "android-x86_64"));
    }

    return ndkCxxPlatformBuilder.build();
  }

  private static UnresolvedNdkCxxPlatform getNdkCxxPlatform(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Path ndkRoot,
      NdkCxxPlatformCompiler compiler,
      NdkCxxRuntime cxxRuntime,
      NdkCxxRuntimeType runtimeType,
      Platform platform,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths,
      String cpuAbi,
      TargetCpuType cpuType,
      String flavorValue) {
    Flavor flavor = InternalFlavor.of(flavorValue);
    String androidPlatform =
        androidConfig
            .getNdkAppPlatformForCpuAbi(cpuAbi)
            .orElse(NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM);
    NdkCxxPlatformTargetConfiguration ndkCxxPlatformTargetConfiguration =
        getTargetConfiguration(cpuType, compiler, androidPlatform);
    return build(
        config,
        androidConfig,
        filesystem,
        flavor,
        platform,
        ndkRoot,
        ndkCxxPlatformTargetConfiguration,
        cxxRuntime,
        runtimeType,
        executableFinder,
        strictToolchainPaths);
  }

  @VisibleForTesting
  static NdkCxxPlatformTargetConfiguration getTargetConfiguration(
      TargetCpuType targetCpuType, NdkCxxPlatformCompiler compiler, String androidPlatform) {
    if (targetCpuType == TargetCpuType.MIPS) {
      throw new AssertionError();
    }
    return NdkCxxPlatformTargetConfiguration.of(targetCpuType, androidPlatform, compiler);
  }

  @VisibleForTesting
  static Host getHost(Platform platform) {
    return Objects.requireNonNull(BUILD_PLATFORMS.get(platform));
  }

  @VisibleForTesting
  static UnresolvedNdkCxxPlatform build(
      CxxBuckConfig config,
      AndroidBuckConfig androidConfig,
      ProjectFilesystem filesystem,
      Flavor flavor,
      Platform platform,
      Path ndkRoot,
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxRuntime cxxRuntime,
      NdkCxxRuntimeType runtimeType,
      ExecutableFinder executableFinder,
      boolean strictToolchainPaths) {
    String ndkVersion = readVersion(ndkRoot);
    if (getNdkMajorVersion(ndkVersion) > 17
        && cxxRuntime != NdkCxxRuntime.LIBCXX
        && cxxRuntime != NdkCxxRuntime.SYSTEM) {
      throw new HumanReadableException(
          "C++ runtime %s was removed in Android NDK 18.\n"
              + "Detected Android NDK version is %s.\n"
              + "Configuration needs to be changed in order to build with the current Android NDK",
          cxxRuntime.toString(), ndkVersion);
    }
    // Create a version string to use when generating rule keys via the NDK tools we'll generate
    // below.  This will be used in lieu of hashing the contents of the tools, so that builds from
    // different host platforms (which produce identical output) will share the cache with one
    // another.
    NdkCompilerType compilerType = targetConfiguration.getCompiler().getType();
    String version =
        Joiner.on('-')
            .join(
                ImmutableList.of(
                    readVersion(ndkRoot),
                    targetConfiguration.getToolchain(),
                    targetConfiguration.getTargetAppPlatform(),
                    compilerType,
                    targetConfiguration.getCompiler().getVersion(),
                    targetConfiguration.getCompiler().getGccVersion(),
                    cxxRuntime));

    Host host = getHost(platform);

    NdkCxxToolchainPaths toolchainPaths =
        new NdkCxxToolchainPaths(
            filesystem,
            ndkRoot,
            targetConfiguration,
            host.toString(),
            cxxRuntime,
            strictToolchainPaths,
            getUseUnifiedHeaders(androidConfig, ndkVersion));
    // Sanitized paths will have magic placeholders for parts of the paths that
    // are machine/host-specific. See comments on ANDROID_NDK_ROOT and
    // BUILD_HOST_SUBST above.
    NdkCxxToolchainPaths sanitizedPaths = toolchainPaths.getSanitizedPaths();

    // Build up the map of paths that must be sanitized.
    ImmutableBiMap.Builder<Path, String> sanitizePathsBuilder = ImmutableBiMap.builder();
    sanitizePathsBuilder.put(
        toolchainPaths.getNdkToolRoot(),
        PathFormatter.pathWithUnixSeparators(sanitizedPaths.getNdkToolRoot()));
    if (compilerType != NdkCompilerType.GCC) {
      sanitizePathsBuilder.put(
          toolchainPaths.getNdkGccToolRoot(),
          PathFormatter.pathWithUnixSeparators(sanitizedPaths.getNdkGccToolRoot()));
    }
    sanitizePathsBuilder.put(ndkRoot, AndroidNdkConstants.ANDROID_NDK_ROOT);

    CxxToolProvider.Type type =
        compilerType == NdkCompilerType.CLANG
            ? CxxToolProvider.Type.CLANG
            : CxxToolProvider.Type.GCC;
    ToolProvider ccTool =
        new ConstantToolProvider(
            getCTool(toolchainPaths, compilerType.cc, version, executableFinder));
    ToolProvider cxxTool =
        new ConstantToolProvider(
            getCTool(toolchainPaths, compilerType.cxx, version, executableFinder));
    CompilerProvider cc =
        new CompilerProvider(
            ccTool, () -> type, ToolType.CC, config.getUseDetailedUntrackedHeaderMessages(), true);
    PreprocessorProvider cpp = new PreprocessorProvider(ccTool, type, ToolType.CPP, true);
    CompilerProvider cxx =
        new CompilerProvider(
            cxxTool,
            () -> type,
            ToolType.CXX,
            config.getUseDetailedUntrackedHeaderMessages(),
            true);
    PreprocessorProvider cxxpp = new PreprocessorProvider(cxxTool, type, ToolType.CXXPP, true);

    Optional<SharedLibraryInterfaceParams.Type> sharedLibType = config.getSharedLibraryInterfaces();
    Optional<SharedLibraryInterfaceParams> sharedLibParams = Optional.empty();
    if (sharedLibType.isPresent()
        && sharedLibType.get() != SharedLibraryInterfaceParams.Type.DISABLED) {
      sharedLibParams =
          Optional.of(
              ElfSharedLibraryInterfaceParams.of(
                  new ConstantToolProvider(
                      getGccTool(toolchainPaths, "objcopy", version, executableFinder)),
                  ImmutableList.of(),
                  sharedLibType.get() == SharedLibraryInterfaceParams.Type.DEFINED_ONLY));
    }

    CxxPlatform.Builder cxxPlatformBuilder = CxxPlatform.builder();
    ImmutableBiMap<Path, String> sanitizePaths = sanitizePathsBuilder.build();
    PrefixMapDebugPathSanitizer compilerDebugPathSanitizer =
        new PrefixMapDebugPathSanitizer(".", sanitizePaths, true);
    cxxPlatformBuilder
        .setFlavor(flavor)
        .setAs(cc)
        .addAllAsflags(StringArg.from(getAsflags(targetConfiguration, toolchainPaths)))
        .setAspp(cpp)
        .setCc(cc)
        .addAllCflags(
            StringArg.from(
                getCCompilationFlags(targetConfiguration, toolchainPaths, androidConfig)))
        .setCpp(cpp)
        .addAllCppflags(
            StringArg.from(
                getCPreprocessorFlags(targetConfiguration, toolchainPaths, androidConfig)))
        .setCxx(cxx)
        .addAllCxxflags(
            StringArg.from(
                getCxxCompilationFlags(targetConfiguration, toolchainPaths, androidConfig)))
        .setCxxpp(cxxpp)
        .addAllCxxppflags(
            StringArg.from(
                getCxxPreprocessorFlags(targetConfiguration, toolchainPaths, androidConfig)))
        .setLd(
            new DefaultLinkerProvider(
                LinkerProvider.Type.GNU,
                new ConstantToolProvider(
                    getCcLinkTool(
                        targetConfiguration,
                        toolchainPaths,
                        compilerType.cxx,
                        version,
                        cxxRuntime,
                        executableFinder)),
                config.shouldCacheLinks()))
        .addAllLdflags(StringArg.from(getLdFlags(targetConfiguration, androidConfig)))
        .setStrip(getGccTool(toolchainPaths, "strip", version, executableFinder))
        .setSymbolNameTool(
            new PosixNmSymbolNameTool(
                new ConstantToolProvider(
                    getGccTool(toolchainPaths, "nm", version, executableFinder))))
        .setAr(
            ArchiverProvider.from(
                new GnuArchiver(getGccTool(toolchainPaths, "ar", version, executableFinder))))
        .setArchiveContents(config.getArchiveContents().orElse(ArchiveContents.NORMAL))
        .setRanlib(
            new ConstantToolProvider(
                getGccTool(toolchainPaths, "ranlib", version, executableFinder)))
        // NDK builds are cross compiled, so the header is the same regardless of the host platform.
        .setCompilerDebugPathSanitizer(compilerDebugPathSanitizer)
        .setSharedLibraryExtension("so")
        .setSharedLibraryVersionedExtensionFormat("so.%s")
        .setStaticLibraryExtension("a")
        .setObjectFileExtension("o")
        .setSharedLibraryInterfaceParams(sharedLibParams)
        .setPublicHeadersSymlinksEnabled(config.getPublicHeadersSymlinksEnabled())
        .setPrivateHeadersSymlinksEnabled(config.getPrivateHeadersSymlinksEnabled())
        .setFilepathLengthLimited(config.getFilepathLengthLimited());

    // Add the NDK root path to the white-list so that headers from the NDK won't trigger the
    // verification warnings.  Ideally, long-term, we'd model NDK libs/headers via automatically
    // generated nodes/descriptions so that they wouldn't need to special case it here.
    HeaderVerification headerVerification = config.getHeaderVerificationOrIgnore();
    try {
      headerVerification =
          headerVerification.withPlatformWhitelist(
              ImmutableList.of(
                  "^"
                      + Pattern.quote(ndkRoot.toRealPath().toString() + File.separatorChar)
                      + ".*"));
    } catch (IOException e) {
      LOG.warn(e, "NDK path could not be resolved: %s", ndkRoot);
    }
    cxxPlatformBuilder.setHeaderVerification(headerVerification);
    LOG.debug("NDK root: %s", ndkRoot.toString());
    LOG.debug(
        "Headers verification platform whitelist: %s", headerVerification.getPlatformWhitelist());

    if (cxxRuntime != NdkCxxRuntime.SYSTEM) {
      cxxPlatformBuilder.putRuntimeLdflags(
          Linker.LinkableDepType.SHARED, StringArg.of("-l" + cxxRuntime.sharedName));
      cxxPlatformBuilder.putRuntimeLdflags(
          Linker.LinkableDepType.STATIC, StringArg.of("-l" + cxxRuntime.staticName));

      if (getNdkMajorVersion(ndkVersion) >= 12 && cxxRuntime == NdkCxxRuntime.LIBCXX) {
        if (getNdkMajorVersion(ndkVersion) < 17
            || targetConfiguration.getTargetArchAbi() == NdkTargetArchAbi.ARMEABI_V7A
            || targetConfiguration.getTargetArchAbi() == NdkTargetArchAbi.X86) {
          cxxPlatformBuilder.putRuntimeLdflags(
              Linker.LinkableDepType.STATIC, StringArg.of("-landroid_support"));
        }
        cxxPlatformBuilder.putRuntimeLdflags(
            Linker.LinkableDepType.STATIC, StringArg.of("-lc++abi"));

        if (targetConfiguration.getTargetArchAbi() == NdkTargetArchAbi.ARMEABI_V7A) {
          // libc++abi on 32-bit ARM depends on the LLVM unwinder; if not explicitly
          // included here, clang++ would resolve references to _Unwind_RaiseException
          // and related symbols with implementations provided by libgcc.a, which is
          // not ABI-compatible with libc++ (and would most likely result in crashes
          // when throwing exceptions).
          cxxPlatformBuilder.putRuntimeLdflags(
              Linker.LinkableDepType.STATIC, StringArg.of("-lunwind"));
          // Don't export symbols from libunwind and libgcc in the linked binary.
          cxxPlatformBuilder.putRuntimeLdflags(
              Linker.LinkableDepType.STATIC, StringArg.of("-Wl,--exclude-libs,libunwind.a"));
          cxxPlatformBuilder.putRuntimeLdflags(
              Linker.LinkableDepType.STATIC, StringArg.of("-Wl,--exclude-libs,libgcc.a"));
        }

        if (targetConfiguration.getTargetArchAbi() == NdkTargetArchAbi.ARMEABI) {
          cxxPlatformBuilder.putRuntimeLdflags(
              Linker.LinkableDepType.STATIC, StringArg.of("-latomic"));
        }
      }
    }

    CxxPlatform cxxPlatform = cxxPlatformBuilder.build();

    NdkCxxPlatform.Builder builder = NdkCxxPlatform.builder();
    builder
        .setCxxPlatform(cxxPlatform)
        .setCxxRuntime(cxxRuntime)
        .setObjdump(getGccTool(toolchainPaths, "objdump", version, executableFinder));
    if ((cxxRuntime != NdkCxxRuntime.SYSTEM) && (runtimeType != NdkCxxRuntimeType.STATIC)) {
      builder.setCxxSharedRuntimePath(
          PathSourcePath.of(
              filesystem,
              toolchainPaths.getCxxRuntimeLibsDirectory().resolve(cxxRuntime.getSoname())));
    }
    return StaticUnresolvedNdkCxxPlatform.of(builder.build());
  }

  @VisibleForTesting
  static boolean getUseUnifiedHeaders(AndroidBuckConfig androidConfig, String ndkVersion) {
    VersionStringComparator comparator = new VersionStringComparator();

    Optional<Boolean> useUnifiedHeadersFromConfig = androidConfig.getNdkUnifiedHeaders();

    if (useUnifiedHeadersFromConfig.isPresent()) {
      boolean useUnifiedHeaders = useUnifiedHeadersFromConfig.get();
      if (useUnifiedHeaders && comparator.compare(ndkVersion, "14") < 0) {
        throw new HumanReadableException(
            "Unified Headers can be only used with Android NDK 14 and newer.\n"
                + "Current configuration has Unified Headers enabled, but detected Android NDK version is %s.\n"
                + "Either change the configuration or upgrade to a newer Android NDK",
            ndkVersion);
      } else if (!useUnifiedHeaders && comparator.compare(ndkVersion, "16") >= 0) {
        throw new HumanReadableException(
            "Non-unified headers were removed in Android NDK 16.\n"
                + "Current configuration has Unified Headers disabled, but detected Android NDK version is %s.\n"
                + "Configuration needs to be changed in order to build with the current Android NDK",
            ndkVersion);
      }
      return useUnifiedHeaders;
    } else {
      // If setting is not set then use Unified Headers starting from NDK 16 (which has no other
      // way), while older NDKs use the deprecated headers.
      return comparator.compare(ndkVersion, "16") >= 0;
    }
  }

  /**
   * It returns the version of the Android NDK located at the {@code ndkRoot} or throws the
   * exception.
   *
   * @param ndkRoot the path where Android NDK is located.
   * @return the version of the Android NDK located in {@code ndkRoot}.
   */
  private static String readVersion(Path ndkRoot) {
    return AndroidNdkResolver.findNdkVersionFromDirectory(ndkRoot).get();
  }

  private static PathSourcePath getToolPath(
      NdkCxxToolchainPaths toolchainPaths, String tool, ExecutableFinder executableFinder) {
    Path expected = toolchainPaths.getToolPath(tool);
    Optional<Path> path = executableFinder.getOptionalExecutable(expected, ImmutableMap.of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return PathSourcePath.of(toolchainPaths.filesystem, path.get());
  }

  private static PathSourcePath getGccToolPath(
      NdkCxxToolchainPaths toolchainPaths, String tool, ExecutableFinder executableFinder) {
    Path expected = toolchainPaths.getGccToolchainBinPath().resolve(tool);
    Optional<Path> path = executableFinder.getOptionalExecutable(expected, ImmutableMap.of());
    Preconditions.checkState(path.isPresent(), expected.toString());
    return PathSourcePath.of(toolchainPaths.filesystem, path.get());
  }

  private static Tool getGccTool(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return VersionedTool.of(tool, getGccToolPath(toolchainPaths, tool, executableFinder), version);
  }

  private static Tool getCTool(
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      ExecutableFinder executableFinder) {
    return VersionedTool.of(tool, getToolPath(toolchainPaths, tool, executableFinder), version);
  }

  private static ImmutableList<String> getCxxRuntimeIncludeFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, NdkCxxToolchainPaths toolchainPaths) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    switch (toolchainPaths.getCxxRuntime()) {
      case GNUSTL:
        flags.add(
            "-isystem",
            PathFormatter.pathWithUnixSeparators(
                toolchainPaths.getCxxRuntimeDirectory().resolve("include")));
        flags.add(
            "-isystem",
            PathFormatter.pathWithUnixSeparators(
                toolchainPaths
                    .getCxxRuntimeDirectory()
                    .resolve("libs")
                    .resolve(targetConfiguration.getTargetArchAbi().toString())
                    .resolve("include")));
        break;
      case LIBCXX:
        String ndkVersion = readVersion(toolchainPaths.getNdkRoot());
        // NDK r12b has a different include path for the LLVM headers
        if (getNdkMajorVersion(ndkVersion) <= 12) {
          flags.add(
              "-isystem",
              PathFormatter.pathWithUnixSeparators(
                  toolchainPaths.getCxxRuntimeDirectory().resolve("libcxx").resolve("include")));
          flags.add(
              "-isystem",
              PathFormatter.pathWithUnixSeparators(
                  toolchainPaths
                      .getCxxRuntimeDirectory()
                      .getParent()
                      .resolve("llvm-libc++abi")
                      .resolve("libcxxabi")
                      .resolve("include")));
        } else {
          flags.add(
              "-isystem",
              PathFormatter.pathWithUnixSeparators(
                  toolchainPaths.getCxxRuntimeDirectory().resolve("include")));
          flags.add(
              "-isystem",
              PathFormatter.pathWithUnixSeparators(
                  toolchainPaths
                      .getCxxRuntimeDirectory()
                      .getParent()
                      .resolve("llvm-libc++abi")
                      .resolve("include")));
        }
        flags.add(
            "-isystem",
            PathFormatter.pathWithUnixSeparators(
                toolchainPaths
                    .getNdkRoot()
                    .resolve("sources")
                    .resolve("android")
                    .resolve("support")
                    .resolve("include")));
        break;
        // $CASES-OMITTED$
      default:
        flags.add(
            "-isystem",
            PathFormatter.pathWithUnixSeparators(
                toolchainPaths.getCxxRuntimeDirectory().resolve("include")));
    }
    return flags.build();
  }

  private static Linker getCcLinkTool(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      String tool,
      String version,
      NdkCxxRuntime cxxRuntime,
      ExecutableFinder executableFinder) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find GCC tools.
    if (targetConfiguration.getCompiler().getType() == NdkCompilerType.CLANG) {
      flags.add(
          "-gcc-toolchain",
          PathFormatter.pathWithUnixSeparators(toolchainPaths.getNdkGccToolRoot()));
    }

    // Set the sysroot to the platform-specific path.
    flags.add(
        "--sysroot=" + PathFormatter.pathWithUnixSeparators(toolchainPaths.getPlatformSysroot()));

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == NdkCompilerType.GCC) {
      flags.add(
          "-B" + PathFormatter.pathWithUnixSeparators(toolchainPaths.getLibexecGccToolPath()),
          "-B" + PathFormatter.pathWithUnixSeparators(toolchainPaths.getLibPath()));
    }

    // Add the path to the C/C++ runtime libraries, if necessary.
    if (cxxRuntime != NdkCxxRuntime.SYSTEM) {
      flags.add(
          "-L" + PathFormatter.pathWithUnixSeparators(toolchainPaths.getCxxRuntimeLibsDirectory()));
    }

    return new GnuLinker(
        VersionedTool.of(
            tool, getToolPath(toolchainPaths, tool, executableFinder), version, flags.build()));
  }

  private static ImmutableList<String> getLdFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.getLinkerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(DEFAULT_COMMON_LDFLAGS)
        .addAll(config.getExtraNdkLdFlags())
        .build();
  }

  /** Flags to be used when either preprocessing or compiling C or C++ sources. */
  private static ImmutableList<String> getCommonFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, NdkCxxToolchainPaths toolchainPaths) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();

    // Clang still needs to find the GCC tools.
    if (targetConfiguration.getCompiler().getType() == NdkCompilerType.CLANG) {
      flags.add(
          "-gcc-toolchain",
          PathFormatter.pathWithUnixSeparators(toolchainPaths.getNdkGccToolRoot()));
    }

    // TODO(#7264008): This was added for windows support but it's not clear why it's needed.
    if (targetConfiguration.getCompiler().getType() == NdkCompilerType.GCC) {
      flags.add(
          "-B" + PathFormatter.pathWithUnixSeparators(toolchainPaths.getLibexecGccToolPath()),
          "-B" + PathFormatter.pathWithUnixSeparators(toolchainPaths.getToolchainBinPath()));
    }

    // Enable default warnings and turn them into errors.
    flags.add("-Wall", "-Werror");

    // NOTE:  We pass all compiler flags to the preprocessor to make sure any necessary internal
    // macros get defined and we also pass the include paths to the to the compiler since we're
    // not whether we're doing combined preprocessing/compiling or not.
    if (targetConfiguration.getCompiler().getType() == NdkCompilerType.CLANG) {
      flags.add("-Wno-unused-command-line-argument");
    }

    // NDK builds enable stack protector and debug symbols by default.
    flags.add("-fstack-protector", "-g3");

    if (toolchainPaths.isUnifiedHeaders()) {
      flags.add("-D__ANDROID_API__=" + targetConfiguration.getTargetAppPlatformLevel());
    }

    return flags.build();
  }

  private static ImmutableList<String> getCommonIncludes(NdkCxxToolchainPaths toolchainPaths) {
    ImmutableList.Builder<String> flags =
        new Builder<String>()
            .add(
                "-isystem",
                PathFormatter.pathWithUnixSeparators(
                    toolchainPaths.getNdkToolRoot().resolve("include")),
                "-isystem",
                PathFormatter.pathWithUnixSeparators(
                    toolchainPaths.getLibPath().resolve("include")),
                "-isystem",
                PathFormatter.pathWithUnixSeparators(
                    toolchainPaths.getIncludeSysroot().resolve("usr").resolve("include")),
                "-isystem",
                PathFormatter.pathWithUnixSeparators(
                    toolchainPaths
                        .getIncludeSysroot()
                        .resolve("usr")
                        .resolve("include")
                        .resolve("linux")));
    if (toolchainPaths.isUnifiedHeaders()) {
      flags.add(
          "-isystem",
          PathFormatter.pathWithUnixSeparators(toolchainPaths.getArchSpecificIncludes()));
    }
    return flags.build();
  }

  private static ImmutableList<String> getAsflags(
      NdkCxxPlatformTargetConfiguration targetConfiguration, NdkCxxToolchainPaths toolchainPaths) {
    return ImmutableList.<String>builder()
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        // Default assembler flags added by the NDK to enforce the NX (no execute) security feature.
        .add("-Xassembler", "--noexecstack")
        .addAll(targetConfiguration.getAssemblerFlags(targetConfiguration.getCompiler().getType()))
        .build();
  }

  // TODO(cjhopman): The way that c/cpp/cxx/cxxpp flags work is rather unintuitive. The
  // documentation states that cflags/cxxflags are added to both preprocess and compile,
  // cppflags/cxxppflags are added only to the preprocessor flags. At runtime, we typically do
  // preprocess+compile, and in that case we're going to add both the preprocess and the compile
  // flags to the command line. Still, BUCK expects that a CxxPlatform can do all of
  // preprocess/compile/preprocess+compile. Many of the flags are duplicated across both preprocess
  // and compile to support that (and then typically our users have to deal with ridiculously long
  // command lines because we only ever do preprocess+compile).
  private static ImmutableList<String> getCPreprocessorFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(getCommonIncludes(toolchainPaths))
        .addAll(DEFAULT_COMMON_CPPFLAGS)
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(DEFAULT_COMMON_CFLAGS)
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(config.getExtraNdkCFlags())
        .build();
  }

  private static ImmutableList<String> getCxxPreprocessorFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(getCxxRuntimeIncludeFlags(targetConfiguration, toolchainPaths));
    flags.addAll(getCommonIncludes(toolchainPaths));
    flags.addAll(DEFAULT_COMMON_CXXPPFLAGS);
    flags.addAll(getCommonFlags(targetConfiguration, toolchainPaths));
    flags.addAll(DEFAULT_COMMON_CXXFLAGS);
    if (targetConfiguration.getCompiler().getType() == NdkCompilerType.GCC) {
      flags.add("-Wno-literal-suffix");
    }
    flags.addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()));
    flags.addAll(config.getExtraNdkCxxFlags());
    return flags.build();
  }

  private static ImmutableList<String> getCCompilationFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(DEFAULT_COMMON_CFLAGS)
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(DEFAULT_COMMON_COMPILER_FLAGS)
        .addAll(config.getExtraNdkCFlags())
        .build();
  }

  private static ImmutableList<String> getCxxCompilationFlags(
      NdkCxxPlatformTargetConfiguration targetConfiguration,
      NdkCxxToolchainPaths toolchainPaths,
      AndroidBuckConfig config) {
    return ImmutableList.<String>builder()
        .addAll(targetConfiguration.getCompilerFlags(targetConfiguration.getCompiler().getType()))
        .addAll(DEFAULT_COMMON_CXXFLAGS)
        .addAll(getCommonFlags(targetConfiguration, toolchainPaths))
        .addAll(DEFAULT_COMMON_COMPILER_FLAGS)
        .addAll(config.getExtraNdkCxxFlags())
        .build();
  }

  /** The OS and Architecture that we're building on. */
  public enum Host {
    DARWIN_X86_64("darwin-x86_64"),
    LINUX_X86_64("linux-x86_64"),
    WINDOWS_X86_64("windows-x86_64"),
    ;

    private final String value;

    Host(String value) {
      this.value = Objects.requireNonNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** The toolchains name for the platform being targeted. */
  public enum ToolchainTarget {
    I686_LINUX_ANDROID("i686-linux-android"),
    X86_64_LINUX_ANDROID("x86_64-linux-android"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
    AARCH64_LINUX_ANDROID("aarch64-linux-android"),
    ;

    private final String value;

    ToolchainTarget(String value) {
      this.value = Objects.requireNonNull(value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  static class NdkCxxToolchainPaths {
    private Path ndkRoot;
    private String ndkVersion;
    private NdkCxxPlatformTargetConfiguration targetConfiguration;
    private String hostName;
    private NdkCxxRuntime cxxRuntime;
    private Map<String, Path> cachedPaths;
    private boolean strict;
    private boolean unifiedHeaders;
    private int ndkMajorVersion;
    private ProjectFilesystem filesystem;

    NdkCxxToolchainPaths(
        ProjectFilesystem filesystem,
        Path ndkRoot,
        NdkCxxPlatformTargetConfiguration targetConfiguration,
        String hostName,
        NdkCxxRuntime cxxRuntime,
        boolean strict,
        boolean unifiedHeaders) {
      this(
          filesystem,
          ndkRoot,
          readVersion(ndkRoot),
          targetConfiguration,
          hostName,
          cxxRuntime,
          strict,
          unifiedHeaders);
    }

    private NdkCxxToolchainPaths(
        ProjectFilesystem filesystem,
        Path ndkRoot,
        String ndkVersion,
        NdkCxxPlatformTargetConfiguration targetConfiguration,
        String hostName,
        NdkCxxRuntime cxxRuntime,
        boolean strict,
        boolean unifiedHeaders) {
      this.filesystem = filesystem;
      this.cachedPaths = new HashMap<>();
      this.strict = strict;
      this.unifiedHeaders = unifiedHeaders;

      this.targetConfiguration = targetConfiguration;
      this.hostName = hostName;
      this.cxxRuntime = cxxRuntime;
      this.ndkRoot = ndkRoot;
      this.ndkVersion = ndkVersion;
      this.ndkMajorVersion = getNdkMajorVersion(ndkVersion);

      Assertions.assertCondition(ndkMajorVersion > 0, "Unknown ndk version: " + ndkVersion);
    }

    NdkCxxToolchainPaths getSanitizedPaths() {
      return new NdkCxxToolchainPaths(
          filesystem,
          Paths.get(AndroidNdkConstants.ANDROID_NDK_ROOT),
          ndkVersion,
          targetConfiguration,
          BUILD_HOST_SUBST,
          cxxRuntime,
          false,
          unifiedHeaders);
    }

    Path processPathPattern(Path root, String pattern, boolean appendExtension) {
      String key = root + "/" + pattern;
      Path result = cachedPaths.get(key);
      if (result == null) {
        String[] segments = pattern.split("/");
        result = root;
        for (String s : segments) {
          if (s.contains("{")) {
            s = s.replace("{toolchain}", targetConfiguration.getToolchain().toString());
            s =
                s.replace(
                    "{toolchain_target}", targetConfiguration.getToolchainTarget().toString());
            s = s.replace("{compiler_version}", targetConfiguration.getCompiler().getVersion());
            s = s.replace("{compiler_type}", targetConfiguration.getCompiler().getType().name);
            s =
                s.replace(
                    "{gcc_compiler_version}", targetConfiguration.getCompiler().getGccVersion());
            s = s.replace("{hostname}", hostName);
            s = s.replace("{target_platform}", targetConfiguration.getTargetAppPlatform());
            s = s.replace("{target_arch}", targetConfiguration.getTargetArch().toString());
            s = s.replace("{target_arch_abi}", targetConfiguration.getTargetArchAbi().toString());
          }
          result = result.resolve(s);
        }
        if (appendExtension) {
          result = appendExtensionIfNeeded(result);
        }
        if (strict) {
          Assertions.assertCondition(result.toFile().exists(), result + " doesn't exist.");
        }
        cachedPaths.put(key, result);
      }
      return result;
    }

    Path processDirectoryPathPattern(Path root, String pattern) {
      return processPathPattern(root, pattern, false);
    }

    Path processExecutablePathPattern(Path root, String pattern) {
      return processPathPattern(root, pattern, true);
    }

    Path processDirectoryPathPattern(String s) {
      return processDirectoryPathPattern(ndkRoot, s);
    }

    private boolean isGcc() {
      return targetConfiguration.getCompiler().getType() == NdkCompilerType.GCC;
    }

    Path getNdkToolRoot() {
      if (isGcc()) {
        return processDirectoryPathPattern(
            "toolchains/{toolchain}-{compiler_version}/prebuilt/{hostname}");
      } else {
        if (ndkMajorVersion < 11) {
          return processDirectoryPathPattern(
              "toolchains/llvm-{compiler_version}/prebuilt/{hostname}");
        } else {
          return processDirectoryPathPattern("toolchains/llvm/prebuilt/{hostname}");
        }
      }
    }

    boolean isUnifiedHeaders() {
      return unifiedHeaders;
    }

    /** Appends an executable extension if the current platform requires it. */
    private static Path appendExtensionIfNeeded(Path path) {
      if (Platform.detect().getType() == PlatformType.WINDOWS) {
        return path.resolveSibling(path.getFileName() + ".exe");
      }
      return path;
    }

    /** @return the path to arch-specific include files; only use with unified headers */
    Path getArchSpecificIncludes() {
      return processDirectoryPathPattern("sysroot/usr/include/{toolchain_target}");
    }

    /**
     * @return the path to use as the system root, targeted to the given target platform and
     *     architecture.
     */
    Path getIncludeSysroot() {
      if (isUnifiedHeaders()) {
        return processDirectoryPathPattern("sysroot");
      }
      return getPlatformSysroot();
    }

    Path getPlatformSysroot() {
      return processDirectoryPathPattern("platforms/{target_platform}/arch-{target_arch}");
    }

    Path getLibexecGccToolPath() {
      Assertions.assertCondition(isGcc());
      if (ndkMajorVersion < 12) {
        return processDirectoryPathPattern(
            getNdkToolRoot(), "libexec/gcc/{toolchain_target}/{compiler_version}");
      } else if (ndkMajorVersion < 18) {
        return processDirectoryPathPattern(
            getNdkToolRoot(), "libexec/gcc/{toolchain_target}/{compiler_version}.x");
      } else {
        return processDirectoryPathPattern(
            getNdkToolRoot(), "lib/gcc/{toolchain_target}/{compiler_version}.x");
      }
    }

    Path getLibPath() {
      String pattern;
      if (isGcc()) {
        if (ndkMajorVersion < 12) {
          pattern = "lib/{compiler_type}/{toolchain_target}/{compiler_version}";
        } else {
          pattern = "lib/{compiler_type}/{toolchain_target}/{compiler_version}.x";
        }
      } else {
        if (ndkMajorVersion < 11) {
          pattern = "lib/{compiler_type}/{compiler_version}";
        } else {
          pattern = "lib64/{compiler_type}/{compiler_version}";
        }
      }
      return processDirectoryPathPattern(getNdkToolRoot(), pattern);
    }

    Path getNdkGccToolRoot() {
      return processDirectoryPathPattern(
          "toolchains/{toolchain}-{gcc_compiler_version}/prebuilt/{hostname}");
    }

    Path getToolchainBinPath() {
      if (isGcc()) {
        return processDirectoryPathPattern(getNdkToolRoot(), "{toolchain_target}/bin");
      } else {
        return processDirectoryPathPattern(getNdkToolRoot(), "bin");
      }
    }

    private Path getGccToolchainBinPath() {
      return processDirectoryPathPattern(getNdkGccToolRoot(), "{toolchain_target}/bin");
    }

    private Path getCxxRuntimeDirectory() {
      if (cxxRuntime == NdkCxxRuntime.GNUSTL) {
        return processDirectoryPathPattern(
            "sources/cxx-stl/" + cxxRuntime.name + "/{gcc_compiler_version}");
      } else {
        return processDirectoryPathPattern("sources/cxx-stl/" + cxxRuntime.name);
      }
    }

    private Path getCxxRuntimeLibsDirectory() {
      return processDirectoryPathPattern(getCxxRuntimeDirectory(), "libs/{target_arch_abi}");
    }

    Path getToolPath(String tool) {
      if (isGcc()) {
        return processExecutablePathPattern(getNdkToolRoot(), "bin/{toolchain_target}-" + tool);
      } else {
        return processExecutablePathPattern(getNdkToolRoot(), "bin/" + tool);
      }
    }

    public Path getNdkRoot() {
      return ndkRoot;
    }

    public NdkCxxRuntime getCxxRuntime() {
      return cxxRuntime;
    }
  }
}
