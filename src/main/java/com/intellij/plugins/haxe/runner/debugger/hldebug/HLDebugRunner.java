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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Debug runner for HashLink applications.
 * 
 * This runner launches the HashLink debugger (hld) and connects to it using
 * the Debug Adapter Protocol (DAP) for debugging .hl bytecode files.
 */
public class HLDebugRunner extends GenericProgramRunner<RunnerSettings> {

    private static final Logger LOG = Logger.getInstance(HLDebugRunner.class);
    
    public static final String RUNNER_ID = "HLDebugRunner";
    public static final int DEFAULT_DEBUG_PORT = 6112;

    @NotNull
    @Override
    public String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        // Only handle debug executor for HashLink configurations
        if (!DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)) {
            return false;
        }
        
        // Check if this is a HashLink run configuration
        return profile instanceof HLRunConfiguration;
    }

    @Nullable
    @Override
    protected RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                              @NotNull ExecutionEnvironment environment) throws ExecutionException {
        Project project = environment.getProject();
        
        if (!(state instanceof HLDebuggerState)) {
            throw new ExecutionException("Invalid run profile state for HashLink debugging");
        }
        
        HLDebuggerState hlState = (HLDebuggerState) state;
        HLRunConfiguration configuration = hlState.getConfiguration();
        
        // Execute the run state to get the process
        ExecutionResult executionResult = state.execute(environment.getExecutor(), this);
        
        if (executionResult == null) {
            throw new ExecutionException("Failed to start HashLink process");
        }
        
        // Create debug session
        XDebugSession debugSession = XDebuggerManager.getInstance(project).startSession(
            environment,
            new XDebugProcessStarter() {
                @NotNull
                @Override
                public XDebugProcess start(@NotNull XDebugSession session) throws ExecutionException {
                    return new HLDebugProcess(
                        session,
                        executionResult,
                        configuration.getHlExecutablePath(),
                        configuration.getProgramPath(),
                        configuration.getWorkingDirectory(),
                        configuration.getDebugPort()
                    );
                }
            }
        );
        
        return debugSession.getRunContentDescriptor();
    }
}
