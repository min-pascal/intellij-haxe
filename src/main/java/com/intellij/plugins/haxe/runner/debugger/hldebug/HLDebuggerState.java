/*
 * Copyright 2024 Haxe Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Execution state for HashLink debugger.
 * 
 * This class prepares the command line and process for running a HashLink
 * application in debug mode.
 */
public class HLDebuggerState extends CommandLineState {

    private static final Logger LOG = Logger.getInstance(HLDebuggerState.class);

    private final HLRunConfiguration configuration;
    private boolean useDebugWait = true; // Default: wait for DAP adapter connection

    public HLDebuggerState(@NotNull ExecutionEnvironment environment,
                           @NotNull HLRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
    }

    public HLRunConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Set whether to use --debug-wait flag.
     * When using LLDB, this should be false since LLDB attaches directly.
     * When using Node.js DAP adapter, this should be true.
     */
    public void setUseDebugWait(boolean useDebugWait) {
        this.useDebugWait = useDebugWait;
    }

    public boolean isUseDebugWait() {
        return useDebugWait;
    }

    @NotNull
    @Override
    protected ProcessHandler startProcess() throws ExecutionException {
        LOG.info("=== HLDebuggerState.startProcess START ===");
        
        // Always start HL ourselves - the DAP adapter has issues on macOS
        // that cause it to hang during native debugger initialization.
        // We start HL directly (debugging features limited on macOS).
        
        // Check if the debug port is already in use and try to clean up
        int debugPort = configuration.getDebugPort();
        if (isPortInUse(debugPort)) {
            LOG.warn("Debug port " + debugPort + " is already in use! Attempting to kill existing process...");
            killProcessOnPort(debugPort);
            
            // Wait a bit for the port to be released
            try { Thread.sleep(500); } catch (InterruptedException e) { /* ignore */ }
            
            if (isPortInUse(debugPort)) {
                throw new ExecutionException("Debug port " + debugPort + " is still in use. Please kill any existing HashLink processes or use a different port.");
            }
            LOG.info("Successfully freed port " + debugPort);
        }
        
        GeneralCommandLine commandLine = createCommandLine();
        
        LOG.info("Starting HashLink process with command line:");
        LOG.info("  Exe: " + commandLine.getExePath());
        LOG.info("  Args: " + commandLine.getParametersList().getList());
        LOG.info("  WorkDir: " + commandLine.getWorkDirectory());
        LOG.info("  Full command: " + commandLine.getCommandLineString());
        
        try {
            OSProcessHandler processHandler = new OSProcessHandler(commandLine);
            ProcessTerminatedListener.attach(processHandler);
            
            // Start the process
            processHandler.startNotify();
            
            LOG.info("ProcessHandler created successfully");
            LOG.info("Process started: " + !processHandler.isProcessTerminated());
            
            return processHandler;
        } catch (Exception e) {
            LOG.error("Failed to start HashLink process", e);
            throw new ExecutionException("Failed to start HashLink: " + e.getMessage(), e);
        }
    }

    @NotNull
    private GeneralCommandLine createCommandLine() throws ExecutionException {
        LOG.info("=== Creating command line ===");
        String hlPath = configuration.getHlExecutablePath();
        String programPath = configuration.getProgramPath();
        String workingDir = configuration.getWorkingDirectory();
        int debugPort = configuration.getDebugPort();
        
        LOG.info("Config values:");
        LOG.info("  hlPath: '" + hlPath + "'");
        LOG.info("  programPath: '" + programPath + "'");
        LOG.info("  workingDir: '" + workingDir + "'");
        LOG.info("  debugPort: " + debugPort);
        LOG.info("  useDebugWait: " + useDebugWait);
        
        if (hlPath == null || hlPath.isEmpty()) {
            LOG.error("HashLink executable path is not configured!");
            throw new ExecutionException("HashLink executable path is not configured");
        }
        
        // Check if HL executable exists
        java.io.File hlFile = new java.io.File(hlPath);
        LOG.info("HL executable exists: " + hlFile.exists() + ", canExecute: " + hlFile.canExecute());
        
        if (programPath == null || programPath.isEmpty()) {
            LOG.error("Program path (.hl file) is not configured!");
            throw new ExecutionException("Program path (.hl file) is not configured");
        }
        
        // Check if program exists
        java.io.File programFile = new java.io.File(programPath);
        LOG.info("Program file exists: " + programFile.exists());
        
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath(hlPath);
        
        // When using LLDB (useDebugWait=false), we DON'T use HashLink's --debug flag
        // because LLDB attaches directly to the process via PID.
        // When using Node.js DAP adapter (useDebugWait=true), we use --debug but NOT --debug-wait
        // because --debug-wait causes the adapter to hang during attach (it blocks in wait() loop)
        if (useDebugWait) {
            // Add debug flag with port for Node.js DAP adapter
            // Note: We intentionally do NOT use --debug-wait because it causes the adapter to hang
            commandLine.addParameter("--debug");
            commandLine.addParameter(String.valueOf(debugPort));
            LOG.info("Using HashLink debug mode (for Node.js DAP adapter)");
        } else {
            LOG.info("NOT using HashLink debug flags (LLDB will attach directly via PID)");
        }
        
        // Add the program to debug
        commandLine.addParameter(programPath);
        
        // Add any program arguments
        String programArgs = configuration.getProgramArguments();
        if (programArgs != null && !programArgs.isEmpty()) {
            for (String arg : programArgs.split("\\s+")) {
                if (!arg.isEmpty()) {
                    commandLine.addParameter(arg);
                }
            }
        }
        
        // Set working directory
        if (workingDir != null && !workingDir.isEmpty()) {
            commandLine.setWorkDirectory(workingDir);
        }
        
        // Set environment variables
        configuration.getEnvironmentVariables().forEach(commandLine::withEnvironment);
        
        return commandLine;
    }

    /**
     * Check if a port is already in use.
     */
    private boolean isPortInUse(int port) {
        try {
            java.net.ServerSocket socket = new java.net.ServerSocket(port);
            socket.close();
            return false; // Port is available
        } catch (java.io.IOException e) {
            return true; // Port is in use
        }
    }

    /**
     * Try to kill any process using the specified port.
     */
    private void killProcessOnPort(int port) {
        try {
            // Use lsof to find the PID
            ProcessBuilder lsofPb = new ProcessBuilder("lsof", "-t", "-i:" + port);
            Process lsofProcess = lsofPb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(lsofProcess.getInputStream()))) {
                String pid;
                while ((pid = reader.readLine()) != null) {
                    pid = pid.trim();
                    if (!pid.isEmpty()) {
                        LOG.info("Killing process " + pid + " on port " + port);
                        ProcessBuilder killPb = new ProcessBuilder("kill", "-9", pid);
                        killPb.start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
            }
            lsofProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Error killing process on port " + port, e);
        }
    }

    @NotNull
    @Override
    public ExecutionResult execute(@NotNull Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
        ProcessHandler processHandler = startProcess();
        ConsoleView console = createConsole(executor);
        
        if (console != null) {
            console.attachToProcess(processHandler);
        }
        
        return new DefaultExecutionResult(console, processHandler);
    }
}
