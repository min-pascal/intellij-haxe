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

    public HLDebuggerState(@NotNull ExecutionEnvironment environment,
                           @NotNull HLRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
    }

    public HLRunConfiguration getConfiguration() {
        return configuration;
    }

    @NotNull
    @Override
    protected ProcessHandler startProcess() throws ExecutionException {
        GeneralCommandLine commandLine = createCommandLine();
        
        LOG.info("Starting HashLink process: " + commandLine.getCommandLineString());
        
        OSProcessHandler processHandler = new OSProcessHandler(commandLine);
        ProcessTerminatedListener.attach(processHandler);
        
        return processHandler;
    }

    @NotNull
    private GeneralCommandLine createCommandLine() throws ExecutionException {
        String hlPath = configuration.getHlExecutablePath();
        String programPath = configuration.getProgramPath();
        String workingDir = configuration.getWorkingDirectory();
        int debugPort = configuration.getDebugPort();
        
        if (hlPath == null || hlPath.isEmpty()) {
            throw new ExecutionException("HashLink executable path is not configured");
        }
        
        if (programPath == null || programPath.isEmpty()) {
            throw new ExecutionException("Program path (.hl file) is not configured");
        }
        
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath(hlPath);
        
        // Add debug flag with port
        commandLine.addParameter("--debug");
        commandLine.addParameter(String.valueOf(debugPort));
        
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
