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
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Execution stack for HashLink debugging.
 * 
 * Represents the call stack for a specific thread.
 */
public class HLExecutionStack extends XExecutionStack {
    
    private final HLDebugProcessInterface debugProcess;
    private final int threadId;
    private final List<HLStackFrame> frames;
    
    public HLExecutionStack(@NotNull HLDebugProcessInterface debugProcess,
                            int threadId,
                            @Nullable List<Map<String, Object>> stackFrames) {
        super("Thread " + threadId);
        this.debugProcess = debugProcess;
        this.threadId = threadId;
        this.frames = new ArrayList<>();
        
        if (stackFrames != null) {
            for (Map<String, Object> frameData : stackFrames) {
                frames.add(new HLStackFrame(debugProcess, frameData));
            }
        }
    }
    
    @Nullable
    @Override
    public XStackFrame getTopFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }
    
    @Override
    public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
        if (firstFrameIndex < frames.size()) {
            List<HLStackFrame> subset = frames.subList(firstFrameIndex, frames.size());
            container.addStackFrames(subset, true);
        } else {
            container.addStackFrames(List.of(), true);
        }
    }
    
    public int getThreadId() {
        return threadId;
    }
}
