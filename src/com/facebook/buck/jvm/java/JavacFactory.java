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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.util.MoreFunctions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import java.util.function.Function;

public final class JavacFactory {
  private final Function<TargetConfiguration, JavacProvider> javacProvider;

  public JavacFactory(Function<TargetConfiguration, JavacProvider> javacProvider) {
    this.javacProvider = javacProvider;
  }

  /** Returns either the default javac or one created from the provided args. */
  public Javac create(
      SourcePathRuleFinder ruleFinder, TargetConfiguration toolchainTargetConfiguration) {
    return javacProvider.apply(toolchainTargetConfiguration).resolve(ruleFinder);
  }

  /** Creates a JavacFactory for the default Java toolchain. */
  public static JavacFactory getDefault(ToolchainProvider toolchainProvider) {
    return new JavacFactory(
        MoreFunctions.memoize(
            toolchainTargetConfiguration ->
                toolchainProvider
                    .getByName(
                        JavaToolchain.DEFAULT_NAME,
                        toolchainTargetConfiguration,
                        JavaToolchain.class)
                    .getJavacProvider()));
  }

  /**
   * Adds the parse time deps required for javac based on the args. If the args has a spec for
   * javac, we assume that the parse time deps will be derived elsewhere.
   */
  public void addParseTimeDeps(
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      TargetConfiguration toolchainTargetConfiguration) {
    javacProvider.apply(toolchainTargetConfiguration).addParseTimeDeps(targetGraphOnlyDepsBuilder);
  }

  public ImmutableSet<BuildRule> getBuildDeps(
      SourcePathRuleFinder ruleFinder, TargetConfiguration toolchainTargetConfiguration) {
    return javacProvider.apply(toolchainTargetConfiguration).getBuildDeps(ruleFinder);
  }
}
