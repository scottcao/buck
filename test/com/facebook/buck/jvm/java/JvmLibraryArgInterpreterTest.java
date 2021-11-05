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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.testutil.TemporaryPaths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JvmLibraryArgInterpreterTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private JavacOptions defaults;
  private ActionGraphBuilder graphBuilder;
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolverAdapter sourcePathResolverAdapter;

  @Before
  public void createHelpers() {
    defaults =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("8").setTargetLevel("8").build())
            .build();
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = graphBuilder;
    sourcePathResolverAdapter = ruleFinder.getSourcePathResolver();
  }

  @Test
  public void javaVersionSetsBothSourceAndTargetLevels() {
    // Set in the past, so if we ever bump the default....
    JvmLibraryArg arg = ExampleJvmLibraryArg.builder().setName("foo").setJavaVersion("1.4").build();

    JavacOptions options = createJavacOptions(arg);

    assertEquals("1.4", options.getLanguageLevelOptions().getSourceLevel());
    assertEquals("1.4", options.getLanguageLevelOptions().getTargetLevel());
  }

  @Test
  public void settingJavaVersionAndSourceLevelIsAnError() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setSource("1.4")
            .setJavaVersion("1.4")
            .build();

    try {
      createJavacOptions(arg);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }

  @Test
  public void settingJavaVersionAndTargetLevelIsAnError() {
    JvmLibraryArg arg =
        ExampleJvmLibraryArg.builder()
            .setName("foo")
            .setTarget("1.4")
            .setJavaVersion("1.4")
            .build();

    try {
      createJavacOptions(arg);
      fail();
    } catch (HumanReadableException e) {
      assertTrue(
          e.getMessage(),
          e.getHumanReadableErrorMessage().contains("either source and target or java_version"));
    }
  }

  private JavacOptions createJavacOptions(JvmLibraryArg arg) {
    return JavacOptionsFactory.create(
        defaults, BuildTargetFactory.newInstance("//not:real"), graphBuilder, tmp.getRoot(), arg);
  }

  @RuleArg
  interface AbstractExampleJvmLibraryArg extends JvmLibraryArg {}
}
