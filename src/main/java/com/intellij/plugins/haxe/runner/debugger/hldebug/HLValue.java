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
import com.intellij.xdebugger.frame.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Map;

/**
 * Value representation for HashLink debugging.
 * 
 * Represents a variable or expression result in the debugger.
 */
public class HLValue extends XNamedValue {
    
    private final HLDebugProcessInterface debugProcess;
    private final String value;
    private final String type;
    private final int variablesReference;
    
    public HLValue(@NotNull HLDebugProcessInterface debugProcess,
                   @NotNull String name,
                   @NotNull String value,
                   @Nullable String type,
                   int variablesReference) {
        super(name);
        this.debugProcess = debugProcess;
        this.value = value;
        this.type = type;
        this.variablesReference = variablesReference;
    }
    
    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
        Icon icon = getIcon();
        
        node.setPresentation(
            icon,
            type,
            value,
            variablesReference > 0 // hasChildren
        );
    }
    
    private Icon getIcon() {
        if (type == null) {
            return AllIcons.Debugger.Value;
        }
        
        String typeLower = type.toLowerCase();
        
        if (typeLower.contains("int") || typeLower.contains("float") || typeLower.contains("number")) {
            return AllIcons.Debugger.Db_primitive;
        } else if (typeLower.equals("string") || typeLower.equals("bool") || typeLower.equals("boolean")) {
            return AllIcons.Debugger.Db_primitive;
        } else if (typeLower.contains("array") || typeLower.contains("list")) {
            return AllIcons.Debugger.Db_array;
        } else if (typeLower.contains("function") || typeLower.contains("closure")) {
            return AllIcons.Nodes.Function;
        } else if (typeLower.equals("null") || typeLower.equals("void")) {
            return AllIcons.Debugger.Db_primitive;
        }
        
        return AllIcons.Debugger.Value;
    }
    
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (variablesReference <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true);
            return;
        }
        
        debugProcess.getVariables(variablesReference).thenAccept(response -> {
            if (response == null || !response.isSuccess()) {
                node.setErrorMessage("Failed to get children");
                return;
            }
            
            List<Map<String, Object>> variables = response.getBody("variables");
            if (variables == null || variables.isEmpty()) {
                node.addChildren(XValueChildrenList.EMPTY, true);
                return;
            }
            
            XValueChildrenList children = new XValueChildrenList();
            
            for (Map<String, Object> variable : variables) {
                String varName = getString(variable, "name", "?");
                String varValue = getString(variable, "value", "?");
                String varType = getString(variable, "type", null);
                int varRef = getInt(variable, "variablesReference", 0);
                
                children.add(varName, new HLValue(debugProcess, varName, varValue, varType, varRef));
            }
            
            node.addChildren(children, true);
        });
    }
    
    @Nullable
    @Override
    public XValueModifier getModifier() {
        // TODO: Implement value modification via DAP setVariable request
        return null;
    }
    
    // Utility methods
    
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
