package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * Configuration interface for HashLink debugging.
 * <p>
 * Implemented by {@link HLRunConfiguration} for the dedicated "HashLink Debug" run configuration,
 * and by {@link HLAutoDebugConfig} for automatic detection from Haxe module settings.
 */
public interface HLDebugConfig {

  /** Path to the HashLink VM executable (e.g. "hl" or "/usr/local/bin/hl"). */
  String getHlExecutablePath();

  /** Path to the compiled .hl program file. */
  String getProgramPath();

  /** Working directory for the HL process. May be null/empty to use project base path. */
  String getWorkingDirectory();

  /** Path to the DAP debug adapter script (adapter.js). */
  String getAdapterPath();

  /** Path to the Node.js executable. */
  String getNodePath();

  /** Source paths for the debugger to resolve breakpoint locations. */
  List<String> getSourcePaths();

  /** Debug port for the DAP adapter to connect on. Default: 6112. */
  int getDebugPort();

  /** Additional program arguments. May be null/empty. */
  String getProgramArguments();

  /** The IntelliJ project. */
  Project getProject();
}
