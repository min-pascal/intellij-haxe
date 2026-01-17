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

/**
 * Base class for DAP (Debug Adapter Protocol) messages.
 * 
 * DAP uses JSON-RPC-like messages with a header specifying content length.
 * Format: "Content-Length: <length>\r\n\r\n<JSON body>"
 */
public abstract class DAPMessage {
    protected int seq;
    protected String type;
    
    public DAPMessage(String type) {
        this.type = type;
    }
    
    public int getSeq() {
        return seq;
    }
    
    public void setSeq(int seq) {
        this.seq = seq;
    }
    
    public String getType() {
        return type;
    }
    
    /**
     * Convert this message to a JSON object for sending.
     */
    public abstract JsonObject toJson();
    
    /**
     * Encode this message for sending over the wire.
     * Format: "Content-Length: <length>\r\n\r\n<JSON body>"
     */
    public String encode() {
        String json = toJson().toString();
        return "Content-Length: " + json.length() + "\r\n\r\n" + json;
    }
}
