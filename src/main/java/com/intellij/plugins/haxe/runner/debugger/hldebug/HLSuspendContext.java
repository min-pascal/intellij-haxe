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

import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Suspend context for HashLink debugging.
 * 
 * Represents the state when the debugger has stopped (breakpoint, step, etc.)
 */
public class HLSuspendContext extends XSuspendContext {
    
    private final HLDebugProcessInterface debugProcess;
    private final int threadId;
    private final List<Map<String, Object>> stackFrames;
    private final String stopReason;
    private final HLExecutionStack executionStack;
    
    public HLSuspendContext(@NotNull HLDebugProcessInterface debugProcess,
                            int threadId,
                            @Nullable List<Map<String, Object>> stackFrames,
                            @NotNull String stopReason) {
        this.debugProcess = debugProcess;
        this.threadId = threadId;
        this.stackFrames = stackFrames;
        this.stopReason = stopReason;
        this.executionStack = new HLExecutionStack(debugProcess, threadId, stackFrames);
    }
    
    @NotNull
    @Override
    public XExecutionStack getActiveExecutionStack() {
        return executionStack;
    }
    
    @Override
    public XExecutionStack @NotNull [] getExecutionStacks() {
        // For now, just return the single thread
        return new XExecutionStack[] { executionStack };
    }
    
    public int getActiveThreadId() {
        return threadId;
    }
    
    public String getStopReason() {
        return stopReason;
    }
}
