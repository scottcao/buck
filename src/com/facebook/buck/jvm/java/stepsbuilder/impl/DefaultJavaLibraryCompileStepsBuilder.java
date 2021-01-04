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

package com.facebook.buck.jvm.java.stepsbuilder.impl;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.UnusedDependenciesParams;
import com.facebook.buck.step.isolatedsteps.java.MakeMissingOutputsStep;
import com.google.common.collect.ImmutableMap;

/** Default implementation of {@link JavaLibraryCompileStepsBuilder}. */
abstract class DefaultJavaLibraryCompileStepsBuilder<T extends CompileToJarStepFactory.ExtraParams>
    extends DefaultJavaStepsBuilderBase<T> implements JavaLibraryCompileStepsBuilder {

  DefaultJavaLibraryCompileStepsBuilder(CompileToJarStepFactory<T> configuredCompiler) {
    super(configuredCompiler);
  }

  @Override
  public void addUnusedDependencyStep(
      UnusedDependenciesParams unusedDependenciesParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings) {
    stepsBuilder.add(
        UnusedDependenciesParams.createFinder(unusedDependenciesParams, cellToPathMappings));
  }

  @Override
  public void addMakeMissingOutputsStep(
      RelPath rootOutput, RelPath pathToClassHashes, RelPath annotationsPath) {
    stepsBuilder.add(new MakeMissingOutputsStep(rootOutput, pathToClassHashes, annotationsPath));
  }
}