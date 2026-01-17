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

import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPClient;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for HashLink debug processes.
 * 
 * This interface allows the stack frame and variable inspection classes
 * to work with different debug process implementations (Node.js adapter or LLDB).
 */
public interface HLDebugProcessInterface {
    
    /**
     * Get the DAP client for sending requests.
     */
    @Nullable
    DAPClient getDapClient();
    
    /**
     * Get the current thread ID.
     */
    int getCurrentThreadId();
    
    /**
     * Get scopes for a stack frame.
     */
    CompletableFuture<DAPResponse> getScopes(int frameId);
    
    /**
     * Get variables for a variables reference.
     */
    CompletableFuture<DAPResponse> getVariables(int variablesReference);
    
    /**
     * Evaluate an expression in the context of a frame.
     */
    CompletableFuture<DAPResponse> evaluate(String expression, int frameId, String context);
}
