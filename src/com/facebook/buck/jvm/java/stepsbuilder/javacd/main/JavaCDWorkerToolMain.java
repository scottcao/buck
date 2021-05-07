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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.isolated.DefaultIsolatedEventBus;
import com.facebook.buck.external.log.ExternalLogHandler;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.PipeliningCommand;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.workertool.model.CommandTypeMessage;
import com.facebook.buck.workertool.model.ExecuteCommand;
import com.facebook.buck.workertool.model.ShutdownCommand;
import com.facebook.buck.workertool.model.StartPipelineCommand;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** JavaCD main class */
public class JavaCDWorkerToolMain {

  private static final Logger LOG = Logger.get(JavaCDWorkerToolMain.class);

  private static final NamedPipeFactory NAMED_PIPE_FACTORY = NamedPipeFactory.getFactory();
  private static final DownwardProtocolType DOWNWARD_PROTOCOL_TYPE = DownwardProtocolType.BINARY;
  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DOWNWARD_PROTOCOL_TYPE.getDownwardProtocol();

  /** Main entrypoint of JavaCD worker tool. */
  public static void main(String[] args) {
    WorkerToolParsedEnvs workerToolParsedEnvs =
        WorkerToolParsedEnvs.parse(EnvVariablesProvider.getSystemEnv());
    Console console = createConsole(workerToolParsedEnvs);

    try (NamedPipeWriter eventNamedPipe =
            NAMED_PIPE_FACTORY.connectAsWriter(workerToolParsedEnvs.getEventPipe());
        OutputStream eventsOutputStream = eventNamedPipe.getOutputStream()) {
      // establish downward protocol type
      DOWNWARD_PROTOCOL_TYPE.writeDelimitedTo(eventsOutputStream);
      Logger logger = Logger.get("");
      logger.cleanHandlers();
      logger.addHandler(new ExternalLogHandler(eventsOutputStream, DOWNWARD_PROTOCOL));

      handleCommands(workerToolParsedEnvs, console, eventsOutputStream);

    } catch (Exception e) {
      handleExceptionAndTerminate(Thread.currentThread(), console, e);
    }
    System.exit(0);
  }

  private static Console createConsole(WorkerToolParsedEnvs parsedEnvVars) {
    return new Console(
        parsedEnvVars.getVerbosity(),
        System.out,
        System.err,
        new Ansi(parsedEnvVars.isAnsiTerminal()));
  }

  private static void handleExceptionAndTerminate(
      Thread thread, Console console, Throwable throwable) {
    // Remove an existing `ExternalLogHandler` handler that depend on the closed event pipe stream.
    Logger logger = Logger.get("");
    logger.cleanHandlers();

    String errorMessage = ErrorLogger.getUserFriendlyMessage(throwable);
    // this method logs the message with log.warn that would be noop as all logger handlers have
    // been cleaned and prints the message into a std err.
    console.printErrorText(
        "Failed to execute java compilation action. Thread: "
            + thread
            + System.lineSeparator()
            + errorMessage);
    System.exit(1);
  }

  private static void handleCommands(
      WorkerToolParsedEnvs workerToolParsedEnvs, Console console, OutputStream eventsOutputStream)
      throws Exception {

    BuildId buildUuid = workerToolParsedEnvs.getBuildUuid();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(console);
    Platform platform = Platform.detect();
    // no need to measure thread CPU time as this is an external process and we do not pass thread
    // time back to buck with Downward API
    Clock clock = new DefaultClock(false);

    try (IsolatedEventBus eventBus =
        new DefaultIsolatedEventBus(buildUuid, eventsOutputStream, clock, DOWNWARD_PROTOCOL)) {

      try (NamedPipeReader commandsNamedPipe =
              NAMED_PIPE_FACTORY.connectAsReader(workerToolParsedEnvs.getCommandPipe());
          InputStream commandsInputStream = commandsNamedPipe.getInputStream()) {

        while (true) {
          CommandTypeMessage commandTypeMessage =
              CommandTypeMessage.parseDelimitedFrom(commandsInputStream);
          CommandTypeMessage.CommandType commandType = commandTypeMessage.getCommandType();

          switch (commandType) {
            case EXECUTE_COMMAND:
              ExecuteCommand executeCommand =
                  ExecuteCommand.parseDelimitedFrom(commandsInputStream);
              String actionId = executeCommand.getActionId();
              BuildJavaCommand buildJavaCommand =
                  BuildJavaCommand.parseDelimitedFrom(commandsInputStream);
              LOG.debug("Start executing command with action id: %s", actionId);

              BuildJavaCommandExecutor.executeBuildJavaCommand(
                  actionId,
                  buildJavaCommand,
                  eventsOutputStream,
                  DOWNWARD_PROTOCOL,
                  eventBus,
                  platform,
                  processExecutor,
                  console,
                  clock);
              break;

            case SHUTDOWN_COMMAND:
              ShutdownCommand shutdownCommand =
                  ShutdownCommand.parseDelimitedFrom(commandsInputStream);
              LOG.debug(
                  "Shutdown command received: %s. Stopping javacd worker tool...", shutdownCommand);
              return;

            case START_PIPELINE_COMMAND:
              StartPipelineCommand startPipelineCommand =
                  StartPipelineCommand.parseDelimitedFrom(commandsInputStream);
              List<String> actionIds = startPipelineCommand.getActionIdList();
              PipeliningCommand pipeliningCommand =
                  PipeliningCommand.parseDelimitedFrom(commandsInputStream);
              LOG.debug("Start executing pipelining command with action ids: %s", actionIds);

              PipeliningJavaCommandExecutor.executePipeliningJavaCommand(
                  actionIds,
                  pipeliningCommand,
                  eventsOutputStream,
                  DOWNWARD_PROTOCOL,
                  eventBus,
                  platform,
                  processExecutor,
                  console,
                  clock);
              break;

            case UNKNOWN:
            case UNRECOGNIZED:
            default:
              throw new IllegalStateException(commandType + " is not supported!");
          }
        }
      }
    }
  }
}
