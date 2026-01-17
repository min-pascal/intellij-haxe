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

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPResponse;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Expression evaluator for HashLink debugging.
 * 
 * Handles evaluation of expressions in the watch window, console, and hover.
 */
public class HLDebuggerEvaluator extends XDebuggerEvaluator {
    
    private static final Logger LOG = Logger.getInstance(HLDebuggerEvaluator.class);
    
    private final HLDebugProcessInterface debugProcess;
    private final int frameId;
    
    public HLDebuggerEvaluator(@NotNull HLDebugProcessInterface debugProcess, int frameId) {
        this.debugProcess = debugProcess;
        this.frameId = frameId;
    }
    
    @Override
    public void evaluate(@NotNull String expression,
                         @NotNull XEvaluationCallback callback,
                         @Nullable XSourcePosition expressionPosition) {
        LOG.debug("Evaluating expression: " + expression);
        
        debugProcess.evaluate(expression, frameId, "watch").thenAccept(response -> {
            if (response == null) {
                callback.errorOccurred("Debug session not connected");
                return;
            }
            
            if (!response.isSuccess()) {
                callback.errorOccurred(response.getMessage() != null ? response.getMessage() : "Evaluation failed");
                return;
            }
            
            String result = response.getBodyString("result", "undefined");
            String type = response.getBodyString("type", null);
            int variablesRef = response.getBodyInt("variablesReference", 0);
            
            callback.evaluated(new HLValue(debugProcess, expression, result, type, variablesRef));
        }).exceptionally(e -> {
            LOG.warn("Error evaluating expression: " + expression, e);
            callback.errorOccurred("Error: " + e.getMessage());
            return null;
        });
    }
}
