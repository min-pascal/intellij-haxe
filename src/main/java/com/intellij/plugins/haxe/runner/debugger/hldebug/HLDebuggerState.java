package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.console.ConsoleExecuteAction;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ServerSocket;

public class HLDebuggerState extends CommandLineState {

  private final HLDebugConfig config;

  public HLDebuggerState(HLDebugConfig config, ExecutionEnvironment environment) {
    super(environment);
    this.config = config;
  }

  @NotNull
  @Override
  protected ProcessHandler startProcess() throws ExecutionException {
    // Port-in-use check
    try (ServerSocket socket = new ServerSocket(config.getDebugPort())) {
      // Port is available
    } catch (IOException e) {
      throw new ExecutionException("Debug port " + config.getDebugPort() + " is already in use");
    }

    // Build command line
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(config.getHlExecutablePath());
    commandLine.addParameter("--debug");
    commandLine.addParameter(String.valueOf(config.getDebugPort()));
    commandLine.addParameter("--debug-wait");
    commandLine.addParameter(config.getProgramPath());

    if (config.getProgramArguments() != null && !config.getProgramArguments().isBlank()) {
      String[] args = config.getProgramArguments().split("\\s+");
      for (String arg : args) {
        commandLine.addParameter(arg);
      }
    }

    if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().isBlank()) {
      commandLine.setWorkDirectory(config.getWorkingDirectory());
    } else {
      commandLine.setWorkDirectory(getEnvironment().getProject().getBasePath());
    }

    return new OSProcessHandler(commandLine);
  }

  @NotNull
  @Override
  public ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
    OSProcessHandler processHandler = (OSProcessHandler) startProcess();
    ExecutionConsole console = TextConsoleBuilderFactory.getInstance()
        .createBuilder(getEnvironment().getProject())
        .getConsole();
    return new DefaultExecutionResult(console, processHandler);
  }
}
