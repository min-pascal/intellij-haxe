package com.intellij.plugins.haxe.runner;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Running state for executing a compiled HashLink (.hl) program via the {@code hl} VM.
 */
public class HaxeHashLinkRunningState extends CommandLineState {
  private final Module module;
  @Nullable
  private final String hlOutputPath;
  @NotNull
  private final com.intellij.execution.configuration.EnvironmentVariablesData envData;

  public HaxeHashLinkRunningState(ExecutionEnvironment env, Module module, @Nullable String hlOutputPath) {
    this(env, module, hlOutputPath, com.intellij.execution.configuration.EnvironmentVariablesData.DEFAULT);
  }

  public HaxeHashLinkRunningState(ExecutionEnvironment env, Module module, @Nullable String hlOutputPath,
                                  @NotNull com.intellij.execution.configuration.EnvironmentVariablesData envData) {
    super(env);
    this.module = module;
    this.hlOutputPath = hlOutputPath;
    this.envData = envData;
  }

  @NotNull
  @Override
  protected ProcessHandler startProcess() throws ExecutionException {
    if (hlOutputPath == null || hlOutputPath.isEmpty()) {
      throw new ExecutionException("Cannot determine HashLink output file (.hl). " +
                                   "Make sure your build configuration targets HashLink (-hl flag).");
    }

    String hlExecutable = findHlExecutable();

    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(hlExecutable);
    commandLine.addParameter(hlOutputPath);

    // Set working directory to project base path
    Project project = module.getProject();
    String basePath = project.getBasePath();
    if (basePath != null) {
      commandLine.setWorkDirectory(basePath);
    }

    // Set up library paths so HL can find its shared libraries (.hdll/.dylib)
    setupHlEnvironment(commandLine, hlExecutable);

    // Apply user-configured environment variables on top (and parent-env passing).
    envData.configureCommandLine(commandLine, true);

    final TextConsoleBuilder consoleBuilder =
      TextConsoleBuilderFactory.getInstance().createBuilder(module.getProject());
    setConsoleBuilder(consoleBuilder);

    return new ColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
  }

  /**
   * Attempts to find the HashLink VM executable.
   * Checks the system PATH first, then falls back to common project-local locations.
   */
  private String findHlExecutable() throws ExecutionException {
    // Check HL_BIN environment variable first (set by user's shell config)
    // Use EnvironmentUtil which sources the user's login shell
    String hlBin = getShellEnv("HL_BIN");
    if (hlBin != null && !hlBin.isEmpty() && new File(hlBin).isFile()) {
      return hlBin;
    }

    // Check PATH (using shell-sourced PATH)
    String pathEnv = getShellEnv("PATH");
    if (pathEnv != null) {
      for (String dir : pathEnv.split(File.pathSeparator)) {
        File hl = new File(dir, "hl");
        if (hl.isFile() && hl.canExecute()) {
          return hl.getAbsolutePath();
        }
        // Also check bin/ subdirectory
        File hlBinDir = new File(dir, "bin/hl");
        if (hlBinDir.isFile() && hlBinDir.canExecute()) {
          return hlBinDir.getAbsolutePath();
        }
      }
    }

    // Check project-local hashlink directory
    Project project = module.getProject();
    String basePath = project.getBasePath();
    if (basePath != null) {
      File localHl = new File(basePath, "hashlink/hl");
      if (localHl.isFile() && localHl.canExecute()) {
        return localHl.getAbsolutePath();
      }
    }

    // Fallback — let the OS try to resolve it
    return "hl";
  }

  private static String getShellEnv(String name) {
    try {
      String value = EnvironmentUtil.getValue(name);
      if (value != null && !value.isEmpty()) return value;
    } catch (Exception e) {
      // Fall through
    }
    return System.getenv(name);
  }

  /**
   * Sets up environment variables for a HashLink process, in particular
   * DYLD_LIBRARY_PATH (macOS) / LD_LIBRARY_PATH (Linux) so HL can find its shared libraries.
   */
  private static void setupHlEnvironment(GeneralCommandLine commandLine, String hlExecutablePath) {
    String dylibPath = getShellEnv("DYLD_LIBRARY_PATH");
    if (dylibPath != null && !dylibPath.isEmpty()) {
      commandLine.withEnvironment("DYLD_LIBRARY_PATH", dylibPath);
    } else {
      File hlDir = new File(hlExecutablePath).getParentFile();
      if (hlDir != null && hlDir.isDirectory()) {
        commandLine.withEnvironment("DYLD_LIBRARY_PATH", hlDir.getAbsolutePath());
      }
    }

    String ldPath = getShellEnv("LD_LIBRARY_PATH");
    if (ldPath != null && !ldPath.isEmpty()) {
      commandLine.withEnvironment("LD_LIBRARY_PATH", ldPath);
    } else {
      File hlDir = new File(hlExecutablePath).getParentFile();
      if (hlDir != null && hlDir.isDirectory()) {
        commandLine.withEnvironment("LD_LIBRARY_PATH", hlDir.getAbsolutePath());
      }
    }
  }
}
