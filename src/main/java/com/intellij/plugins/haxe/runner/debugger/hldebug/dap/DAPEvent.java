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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a DAP event message.
 * 
 * Events are sent from the debug adapter to the client (IDE) asynchronously.
 * Common events: stopped, continued, terminated, output, breakpoint, thread
 */
public class DAPEvent extends DAPMessage {
    private String event;
    private JsonObject body;
    
    public DAPEvent() {
        super("event");
    }
    
    public static DAPEvent fromJson(@NotNull JsonObject json) {
        DAPEvent event = new DAPEvent();
        event.seq = json.get("seq").getAsInt();
        event.event = json.get("event").getAsString();
        
        if (json.has("body") && !json.get("body").isJsonNull()) {
            event.body = json.getAsJsonObject("body");
        }
        
        return event;
    }
    
    public String getEvent() {
        return event;
    }
    
    @Nullable
    public JsonObject getBodyJson() {
        return body;
    }
    
    public int getBodyInt(String key, int defaultValue) {
        if (body == null || !body.has(key)) {
            return defaultValue;
        }
        return body.get(key).getAsInt();
    }
    
    public String getBodyString(String key, String defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
            return defaultValue;
        }
        return body.get(key).getAsString();
    }
    
    public boolean getBodyBoolean(String key, boolean defaultValue) {
        if (body == null || !body.has(key)) {
            return defaultValue;
        }
        return body.get(key).getAsBoolean();
    }
    
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getBody(String key) {
        if (body == null || !body.has(key)) {
            return null;
        }
        JsonElement element = body.get(key);
        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                return (T) element.getAsString();
            } else if (element.getAsJsonPrimitive().isNumber()) {
                return (T) Integer.valueOf(element.getAsInt());
            } else if (element.getAsJsonPrimitive().isBoolean()) {
                return (T) Boolean.valueOf(element.getAsBoolean());
            }
        }
        if (element.isJsonArray()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    list.add(jsonObjectToMap(item.getAsJsonObject()));
                }
            }
            return (T) list;
        }
        if (element.isJsonObject()) {
            return (T) jsonObjectToMap(element.getAsJsonObject());
        }
        return null;
    }
    
    private Map<String, Object> jsonObjectToMap(JsonObject obj) {
        Map<String, Object> map = new HashMap<>();
        for (String key : obj.keySet()) {
            JsonElement element = obj.get(key);
            if (element.isJsonNull()) {
                map.put(key, null);
            } else if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isString()) {
                    map.put(key, element.getAsString());
                } else if (element.getAsJsonPrimitive().isNumber()) {
                    map.put(key, element.getAsNumber());
                } else if (element.getAsJsonPrimitive().isBoolean()) {
                    map.put(key, element.getAsBoolean());
                }
            } else if (element.isJsonObject()) {
                map.put(key, jsonObjectToMap(element.getAsJsonObject()));
            } else if (element.isJsonArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonElement item : element.getAsJsonArray()) {
                    if (item.isJsonObject()) {
                        list.add(jsonObjectToMap(item.getAsJsonObject()));
                    } else if (item.isJsonPrimitive()) {
                        if (item.getAsJsonPrimitive().isString()) {
                            list.add(item.getAsString());
                        } else if (item.getAsJsonPrimitive().isNumber()) {
                            list.add(item.getAsNumber());
                        }
                    }
                }
                map.put(key, list);
            }
        }
        return map;
    }
    
    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("seq", seq);
        json.addProperty("type", type);
        json.addProperty("event", event);
        if (body != null) {
            json.add("body", body);
        }
        return json;
    }
}
