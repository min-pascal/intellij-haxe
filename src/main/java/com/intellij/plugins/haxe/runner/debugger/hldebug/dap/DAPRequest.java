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
package com.intellij.plugins.haxe.runner.debugger.hldebug.dap;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a DAP request message.
 * 
 * Requests are sent from the client (IDE) to the debug adapter.
 */
public class DAPRequest extends DAPMessage {
    private final String command;
    private final Map<String, Object> arguments = new HashMap<>();
    
    public DAPRequest(@NotNull String command) {
        super("request");
        this.command = command;
    }
    
    public String getCommand() {
        return command;
    }
    
    public void setArgument(String key, Object value) {
        arguments.put(key, value);
    }
    
    public Object getArgument(String key) {
        return arguments.get(key);
    }
    
    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("seq", seq);
        json.addProperty("type", type);
        json.addProperty("command", command);
        
        if (!arguments.isEmpty()) {
            JsonObject args = new JsonObject();
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                addToJsonObject(args, entry.getKey(), entry.getValue());
            }
            json.add("arguments", args);
        }
        
        return json;
    }
    
    @SuppressWarnings("unchecked")
    private void addToJsonObject(JsonObject obj, String key, Object value) {
        if (value == null) {
            obj.add(key, null);
        } else if (value instanceof String) {
            obj.addProperty(key, (String) value);
        } else if (value instanceof Number) {
            obj.addProperty(key, (Number) value);
        } else if (value instanceof Boolean) {
            obj.addProperty(key, (Boolean) value);
        } else if (value instanceof Map) {
            JsonObject nested = new JsonObject();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                addToJsonObject(nested, entry.getKey(), entry.getValue());
            }
            obj.add(key, nested);
        } else if (value instanceof Iterable) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Object item : (Iterable<?>) value) {
                if (item instanceof String) {
                    arr.add((String) item);
                } else if (item instanceof Number) {
                    arr.add((Number) item);
                } else if (item instanceof Boolean) {
                    arr.add((Boolean) item);
                } else if (item instanceof Map) {
                    JsonObject nested = new JsonObject();
                    for (Map.Entry<String, Object> entry : ((Map<String, Object>) item).entrySet()) {
                        addToJsonObject(nested, entry.getKey(), entry.getValue());
                    }
                    arr.add(nested);
                }
            }
            obj.add(key, arr);
        }
    }
}
