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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.rules.pipeline.CompilationDaemonStep;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.javacd.model.BaseCommandParams;
import com.facebook.buck.javacd.model.BasePipeliningCommand;
import com.facebook.buck.javacd.model.BuildTargetValue;
import com.facebook.buck.javacd.model.LibraryPipeliningCommand;
import com.facebook.buck.javacd.model.PipelineState;
import com.facebook.buck.javacd.model.PipeliningCommand;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.common.AbstractIsolatedExecutionStep;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPool.BorrowedWorkerProcess;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/**
 * JavaCD pipelining step that communicate with javacd WT and could execute java compilation
 * pipelining command.
 *
 * <p>Pipelining command contains up to two protobuf commands:
 *
 * <ol>
 *   <li>Only source-abi.jar
 *   <li>Only library.jar
 *   <li>Combined source-abi.jar and library.jar commands. Order is the following: 1. source-abi, 2.
 *       library. commands.
 */
class JavaCDPipeliningWorkerToolStep extends AbstractIsolatedExecutionStep
    implements CompilationDaemonStep {

  private static final Logger LOG = Logger.get(JavaCDPipeliningWorkerToolStep.class);

  private final JavaCDParams javaCDParams;
  private final PipeliningCommand.Builder builder = PipeliningCommand.newBuilder();
  private final List<AbstractMessage> commands = new ArrayList<>();

  private ImmutableMap<String, Future<ResultEvent>> actionIdToResultEventMap = ImmutableMap.of();
  @Nullable private BorrowedWorkerProcess<WorkerToolExecutor> borrowedWorkerTool;

  public JavaCDPipeliningWorkerToolStep(
      PipelineState pipeliningState,
      boolean hasAnnotationProcessing,
      boolean withDownwardApi,
      BaseCommandParams.SpoolMode spoolMode,
      JavaCDParams javaCDParams) {
    super("javacd_pipelining_wt");
    this.javaCDParams = javaCDParams;

    builder
        .getBaseCommandParamsBuilder()
        .setHasAnnotationProcessing(hasAnnotationProcessing)
        .setWithDownwardApi(withDownwardApi)
        .setSpoolMode(spoolMode);
    builder.setPipeliningState(pipeliningState);
  }

  @Override
  public void appendStepWithCommand(AbstractMessage command) {
    commands.add(command);
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {

    String actionId = context.getActionId();
    ImmutableList<String> launchJavaCDCommand =
        JavaCDWorkerStepUtils.getLaunchJavaCDCommand(javaCDParams);

    if (borrowedWorkerTool == null) {
      Preconditions.checkState(actionIdToResultEventMap.isEmpty());

      WorkerProcessPool<WorkerToolExecutor> workerToolPool =
          JavaCDWorkerStepUtils.getWorkerToolPool(context, launchJavaCDCommand, javaCDParams);
      borrowedWorkerTool =
          JavaCDWorkerStepUtils.borrowWorkerToolWithTimeout(
              workerToolPool, javaCDParams.getBorrowFromPoolTimeoutInSeconds());

      try {
        actionIdToResultEventMap = startExecution(borrowedWorkerTool.get());
      } catch (ExecutionException e) {
        return JavaCDWorkerStepUtils.createFailStepExecutionResult(
            launchJavaCDCommand, actionId, e);
      }
    }

    Future<ResultEvent> resultEventFuture =
        Objects.requireNonNull(
            actionIdToResultEventMap.get(actionId),
            String.format(
                "Cannot find a future for actionId: %s among executing: %s",
                actionId, actionIdToResultEventMap.keySet()));
    try {
      LOG.debug("Waiting for the result event associated with action id: %s", actionId);
      ResultEvent resultEvent = resultEventFuture.get();
      return JavaCDWorkerStepUtils.createStepExecutionResult(
          launchJavaCDCommand, resultEvent, actionId);
    } catch (ExecutionException e) {
      return JavaCDWorkerStepUtils.createFailStepExecutionResult(launchJavaCDCommand, actionId, e);
    }
  }

  private ImmutableMap<String, Future<ResultEvent>> startExecution(
      WorkerToolExecutor workerToolExecutor)
      throws IOException, ExecutionException, InterruptedException {

    Preconditions.checkArgument(
        !commands.isEmpty() && commands.size() <= 2, "Commands size must be equal only to 1 or 2");
    ImmutableList.Builder<String> actionsIdsBuilder = ImmutableList.builder();

    // the first command could be either source-abi or library one
    AbstractMessage command1 = commands.get(0);
    if (command1 instanceof BasePipeliningCommand) {
      BasePipeliningCommand abiCommand = (BasePipeliningCommand) command1;
      builder.setAbiCommand(abiCommand);
      actionsIdsBuilder.add(getActionId(abiCommand.getBuildTargetValue()));
    } else {
      Preconditions.checkState(
          command1 instanceof LibraryPipeliningCommand, "The first command must be a library one");
      LibraryPipeliningCommand libraryCommand = (LibraryPipeliningCommand) command1;
      builder.clearAbiCommand();
      builder.setLibraryCommand(libraryCommand);
      actionsIdsBuilder.add(
          getActionId(libraryCommand.getBasePipeliningCommand().getBuildTargetValue()));
    }

    // the second command could be only library one
    if (commands.size() > 1) {
      AbstractMessage command2 = commands.get(1);
      Preconditions.checkState(
          command2 instanceof LibraryPipeliningCommand, "The second command must be a library one");
      LibraryPipeliningCommand libraryCommand = (LibraryPipeliningCommand) command2;
      builder.setLibraryCommand(libraryCommand);
      actionsIdsBuilder.add(
          getActionId(libraryCommand.getBasePipeliningCommand().getBuildTargetValue()));
    }

    ImmutableList<String> actionIds = actionsIdsBuilder.build();
    PipeliningCommand pipeliningCommand = builder.build();

    ImmutableList<Future<ResultEvent>> futures =
        workerToolExecutor.executePipeliningCommand(actionIds, pipeliningCommand);

    ImmutableMap.Builder<String, Future<ResultEvent>> mapBuilder = ImmutableMap.builder();
    for (int i = 0; i < actionIds.size(); i++) {
      mapBuilder.put(actionIds.get(i), futures.get(i));
    }
    return mapBuilder.build();
  }

  private String getActionId(BuildTargetValue buildTargetValue) {
    return buildTargetValue.getFullyQualifiedName();
  }

  @Override
  public void close() {
    if (borrowedWorkerTool != null) {
      borrowedWorkerTool.close();
      borrowedWorkerTool = null;
    }
    actionIdToResultEventMap = ImmutableMap.of();
  }
}
