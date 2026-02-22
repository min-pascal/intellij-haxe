package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.buildsystem.hxml.model.HXMLProjectModel;
import com.intellij.plugins.haxe.compilation.HaxeCompilerUtil;
import com.intellij.plugins.haxe.ide.module.HaxeModuleSettings;
import com.intellij.plugins.haxe.util.HaxeFileUtil;
import com.intellij.util.EnvironmentUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configured {@link HLDebugConfig} that derives HashLink debug settings
 * from the Haxe module configuration.
 * <p>
 * Used when debugging HL targets via the standard "Haxe Application" run configuration,
 * auto-detecting the HL executable, .hl program output, adapter.js location, and source paths.
 */
public class HLAutoDebugConfig implements HLDebugConfig {

  private static final Logger LOG = Logger.getInstance(HLAutoDebugConfig.class);

  private static final int DEFAULT_DEBUG_PORT = 6112;

  private final Project project;
  private final Module module;
  private final String programPath;
  private final String workingDirectory;
  private final int debugPort;

  /**
   * @param project          the IntelliJ project
   * @param module           the Haxe module
   * @param debugPort        debug port (use {@link #DEFAULT_DEBUG_PORT} if 0 or negative)
   */
  public HLAutoDebugConfig(Project project, Module module, int debugPort) {
    this.project = project;
    this.module = module;
    this.programPath = resolveHlProgramPath(project, module);
    this.workingDirectory = project.getBasePath();
    this.debugPort = debugPort > 0 ? debugPort : DEFAULT_DEBUG_PORT;
  }

  /**
   * Resolves the .hl output path from the module settings.
   * <p>
   * For HXML builds, parses the HXML file for the {@code -hl} flag.
   * Falls back to {@link HaxeCompilerUtil#calculateCompilerOutput} for other build types.
   */
  private static String resolveHlProgramPath(Project project, Module module) {
    HaxeModuleSettings settings = HaxeModuleSettings.getInstance(module);

    // For HXML builds, try to parse the -hl output directly from the HXML file
    if (settings.isUseHxmlToBuild()) {
      String hxmlPath = settings.getHxmlPath();
      if (hxmlPath != null && !hxmlPath.isEmpty()) {
        String hlOutput = ReadAction.compute(() -> {
          VirtualFile hxmlFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
              .findFileByPath(hxmlPath);
          if (hxmlFile == null) {
            // Try as a path relative to project
            String basePath = project.getBasePath();
            if (basePath != null) {
              hxmlFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                  .findFileByPath(basePath + "/" + hxmlPath);
            }
          }
          if (hxmlFile == null) {
            hxmlFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(hxmlPath);
          }
          if (hxmlFile != null) {
            HXMLProjectModel model = HXMLProjectModel.create(project, hxmlFile);
            if (model != null) {
              return model.getProperty("-hl");
            }
          }
          return null;
        });

        if (hlOutput != null && !hlOutput.isEmpty()) {
          // If relative, resolve against project base path
          if (!new File(hlOutput).isAbsolute() && project.getBasePath() != null) {
            return project.getBasePath() + "/" + hlOutput;
          }
          return hlOutput;
        }
      }
    }

    // For user-properties builds, try the configured output file name
    if (settings.isUseUserPropertiesToBuild()) {
      String outputFile = settings.getOutputFileName();
      if (outputFile != null && !outputFile.isEmpty()) {
        if (new File(outputFile).isAbsolute()) {
          return outputFile;
        }
        String outputFolder = settings.getOutputFolder();
        if (outputFolder != null && !outputFolder.isEmpty()) {
          return outputFolder + "/" + outputFile;
        }
        if (project.getBasePath() != null) {
          return project.getBasePath() + "/" + outputFile;
        }
        return outputFile;
      }
    }

    // Fallback: try calculateCompilerOutput (now safe since we fixed the null-safety bug)
    try {
      return HaxeCompilerUtil.calculateCompilerOutput(module);
    } catch (Exception e) {
      LOG.warn("Failed to calculate compiler output for HL target", e);
      return null;
    }
  }

  @Override
  public String getHlExecutablePath() {
    // Check HL_BIN environment variable first (set by user's shell config)
    // Use EnvironmentUtil which sources the user's login shell, since
    // the IDE process may not directly inherit interactive shell variables.
    String hlBin = getShellEnv("HL_BIN");
    if (hlBin != null && !hlBin.isEmpty() && new File(hlBin).isFile()) {
      return hlBin;
    }

    // Try to find 'hl' on the PATH (using shell-sourced PATH)
    String hlOnPath = findOnPath("hl");
    if (hlOnPath != null) return hlOnPath;

    // Fallback: check if there's a local hashlink directory in the project
    String basePath = project.getBasePath();
    if (basePath != null) {
      File localHl = new File(basePath, "hashlink/hl");
      if (localHl.isFile() && localHl.canExecute()) {
        return localHl.getAbsolutePath();
      }
    }

    // Last resort: just "hl" and hope it's on the path
    return "hl";
  }

  @Override
  public String getProgramPath() {
    return programPath;
  }

  @Override
  public String getWorkingDirectory() {
    return workingDirectory;
  }

  @Override
  public String getAdapterPath() {
    // 1. Check HASHLINK_DEBUGGER_ADAPTER environment variable
    String envAdapter = System.getenv("HASHLINK_DEBUGGER_ADAPTER");
    if (envAdapter != null && !envAdapter.isEmpty() && new File(envAdapter).isFile()) {
      return envAdapter;
    }

    // 2. Look for hashlink-debugger/adapter.js in project root and parent directories
    String basePath = project.getBasePath();
    if (basePath != null) {
      File dir = new File(basePath);
      // Walk up to 5 parent directories looking for the adapter
      for (int i = 0; i < 5 && dir != null; i++) {
        File adapter = new File(dir, "hashlink-debugger/adapter.js");
        if (adapter.isFile()) {
          return adapter.getAbsolutePath();
        }
        dir = dir.getParentFile();
      }
    }

    // 3. Check next to the HL executable (some installs bundle the debugger alongside HL)
    String hlBin = System.getenv("HL_BIN");
    if (hlBin != null && !hlBin.isEmpty()) {
      File hlDir = new File(hlBin).getParentFile();
      if (hlDir != null) {
        // Check sibling directories
        File parentDir = hlDir.getParentFile();
        if (parentDir != null) {
          File adapter = new File(parentDir, "hashlink-debugger/adapter.js");
          if (adapter.isFile()) {
            return adapter.getAbsolutePath();
          }
        }
      }
    }

    // 4. Try common user-home locations
    String home = System.getProperty("user.home");
    if (home != null) {
      String[] candidates = {
          home + "/hashlink-debugger/adapter.js",
          home + "/.haxelib/hashlink-debugger/adapter.js",
      };
      for (String candidate : candidates) {
        if (new File(candidate).isFile()) {
          return candidate;
        }
      }
    }

    LOG.warn("Could not auto-detect hashlink-debugger adapter.js path. " +
             "Please use the dedicated 'HashLink Debug' run configuration to specify it manually.");
    return "adapter.js";
  }

  @Override
  public String getNodePath() {
    // Try to find 'node' on the PATH (using shell-sourced PATH)
    String nodeOnPath = findOnPath("node");
    if (nodeOnPath != null) return nodeOnPath;
    return "node";
  }

  @Override
  public List<String> getSourcePaths() {
    List<String> paths = new ArrayList<>();
    VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(false);
    for (VirtualFile root : sourceRoots) {
      paths.add(root.getPath());
    }
    // If no source roots configured, use the project base path
    if (paths.isEmpty() && project.getBasePath() != null) {
      paths.add(project.getBasePath());
    }
    return paths;
  }

  @Override
  public int getDebugPort() {
    return debugPort;
  }

  @Override
  public String getProgramArguments() {
    return "";
  }

  @Override
  public Project getProject() {
    return project;
  }

  /**
   * Gets an environment variable, first from IntelliJ's shell-sourced environment
   * (which loads the user's login shell), then falling back to {@code System.getenv}.
   */
  private static String getShellEnv(String name) {
    // EnvironmentUtil.getEnvironmentMap() sources the user's login shell
    // and includes variables defined in .zshrc/.bashrc etc.
    try {
      String value = EnvironmentUtil.getValue(name);
      if (value != null && !value.isEmpty()) return value;
    } catch (Exception e) {
      // Fall through to System.getenv
    }
    return System.getenv(name);
  }

  /**
   * Attempts to find an executable on the system PATH.
   * Uses the shell-sourced PATH from {@link EnvironmentUtil} for reliability.
   *
   * @param executable the executable name
   * @return the absolute path if found, or null
   */
  private static String findOnPath(String executable) {
    // Use EnvironmentUtil to get the PATH as the user's shell sees it
    String pathEnv = getShellEnv("PATH");
    if (pathEnv == null) return null;
    for (String dir : pathEnv.split(File.pathSeparator)) {
      File file = new File(dir, executable);
      if (file.isFile() && file.canExecute()) {
        return file.getAbsolutePath();
      }
      // Also check bin/ subdirectory (common for hashlink builds)
      File binFile = new File(dir, "bin/" + executable);
      if (binFile.isFile() && binFile.canExecute()) {
        return binFile.getAbsolutePath();
      }
    }
    return null;
  }
}
