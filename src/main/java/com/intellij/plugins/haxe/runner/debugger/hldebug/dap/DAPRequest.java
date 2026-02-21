package com.intellij.plugins.haxe.runner.debugger.hldebug.dap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a DAP request message sent from client to adapter.
 * Uses LinkedHashMap to maintain stable serialization order for arguments.
 */
public class DAPRequest extends DAPMessage {

  private int seq;
  private final String command;
  private final Map<String, Object> arguments = new LinkedHashMap<>();

  /**
   * Creates a new DAP request with the specified command.
   *
   * @param command The DAP command name
   */
  public DAPRequest(String command) {
    this.command = command;
  }

  /**
   * Sets the sequence number for this request.
   *
   * @param seq The sequence number
   */
  public void setSeq(int seq) {
    this.seq = seq;
  }

  /**
   * Adds an argument to this request.
   *
   * @param key   The argument name
   * @param value The argument value
   */
  public void setArgument(String key, Object value) {
    arguments.put(key, value);
  }

  /**
   * Gets the command name.
   *
   * @return The command name
   */
  public String getCommand() {
    return command;
  }

  @Override
  public JsonObject toJson() {
    JsonObject obj = new JsonObject();
    obj.addProperty("type", "request");
    obj.addProperty("seq", seq);
    obj.addProperty("command", command);
    
    if (!arguments.isEmpty()) {
      obj.add("arguments", new Gson().toJsonTree(arguments));
    }
    
    return obj;
  }
}
