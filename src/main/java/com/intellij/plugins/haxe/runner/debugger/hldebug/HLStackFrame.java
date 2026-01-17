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

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Stack frame for HashLink debugging.
 * 
 * Represents a single frame in the call stack.
 */
public class HLStackFrame extends XStackFrame {
    
    private static final Logger LOG = Logger.getInstance(HLStackFrame.class);
    
    private final HLDebugProcessInterface debugProcess;
    private final int frameId;
    private final String name;
    private final String sourcePath;
    private final int line;
    private final int column;
    private final XSourcePosition sourcePosition;
    
    public HLStackFrame(@NotNull HLDebugProcessInterface debugProcess, @NotNull Map<String, Object> frameData) {
        this.debugProcess = debugProcess;
        
        // Extract frame data from DAP response
        this.frameId = getInt(frameData, "id", 0);
        this.name = getString(frameData, "name", "unknown");
        this.line = getInt(frameData, "line", 1);
        this.column = getInt(frameData, "column", 0);
        
        // Extract source info
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) frameData.get("source");
        if (source != null) {
            this.sourcePath = getString(source, "path", null);
        } else {
            this.sourcePath = null;
        }
        
        // Create source position
        this.sourcePosition = createSourcePosition();
    }
    
    @Nullable
    private XSourcePosition createSourcePosition() {
        if (sourcePath == null) {
            return null;
        }
        
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(sourcePath);
        if (file == null) {
            LOG.debug("Could not find file: " + sourcePath);
            return null;
        }
        
        // DAP uses 1-based lines, IntelliJ uses 0-based
        return XDebuggerUtil.getInstance().createPosition(file, line - 1, column > 0 ? column - 1 : 0);
    }
    
    @Nullable
    @Override
    public XSourcePosition getSourcePosition() {
        return sourcePosition;
    }
    
    @Nullable
    @Override
    public XDebuggerEvaluator getEvaluator() {
        return new HLDebuggerEvaluator(debugProcess, frameId);
    }
    
    @Override
    public void customizePresentation(@NotNull ColoredTextContainer component) {
        component.append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
        
        if (sourcePath != null) {
            // Extract filename
            int lastSlash = sourcePath.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? sourcePath.substring(lastSlash + 1) : sourcePath;
            
            component.append(" at ", SimpleTextAttributes.GRAY_ATTRIBUTES);
            component.append(fileName + ":" + line, SimpleTextAttributes.GRAY_ATTRIBUTES);
        }
        
        component.setIcon(AllIcons.Debugger.Frame);
    }
    
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        // Fetch scopes and their variables
        debugProcess.getScopes(frameId).thenAccept(response -> {
            if (response == null || !response.isSuccess()) {
                node.setErrorMessage("Failed to get variables");
                return;
            }
            
            List<Map<String, Object>> scopes = response.getBody("scopes");
            if (scopes == null || scopes.isEmpty()) {
                node.addChildren(XValueChildrenList.EMPTY, true);
                return;
            }
            
            XValueChildrenList children = new XValueChildrenList();
            
            // Process each scope
            final int[] pendingScopes = {scopes.size()};
            
            for (Map<String, Object> scope : scopes) {
                String scopeName = getString(scope, "name", "Scope");
                int variablesRef = getInt(scope, "variablesReference", 0);
                
                if (variablesRef > 0) {
                    // Fetch variables for this scope
                    debugProcess.getVariables(variablesRef).thenAccept(varResponse -> {
                        if (varResponse != null && varResponse.isSuccess()) {
                            List<Map<String, Object>> variables = varResponse.getBody("variables");
                            if (variables != null) {
                                for (Map<String, Object> variable : variables) {
                                    String varName = getString(variable, "name", "?");
                                    String varValue = getString(variable, "value", "?");
                                    String varType = getString(variable, "type", null);
                                    int varRef = getInt(variable, "variablesReference", 0);
                                    
                                    children.add(varName, new HLValue(debugProcess, varName, varValue, varType, varRef));
                                }
                            }
                        }
                        
                        synchronized (pendingScopes) {
                            pendingScopes[0]--;
                            if (pendingScopes[0] == 0) {
                                node.addChildren(children, true);
                            }
                        }
                    });
                } else {
                    synchronized (pendingScopes) {
                        pendingScopes[0]--;
                        if (pendingScopes[0] == 0) {
                            node.addChildren(children, true);
                        }
                    }
                }
            }
        });
    }
    
    public int getFrameId() {
        return frameId;
    }
    
    // Utility methods for extracting data from maps
    
    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
}
