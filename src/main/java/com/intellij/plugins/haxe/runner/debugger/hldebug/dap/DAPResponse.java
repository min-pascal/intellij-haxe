package com.intellij.plugins.haxe.runner.debugger.hldebug.dap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a DAP response message received from the adapter.
 * Provides typed accessor methods for common body fields.
 */
public class DAPResponse extends DAPMessage {

  private int requestSeq;
  private boolean success;
  private String command;
  private String message;
  private JsonObject body;

  /**
   * Private constructor used by fromJson factory method.
   */
  private DAPResponse() {
  }

  /**
   * Creates a DAPResponse from a parsed JSON object.
   *
   * @param obj The JSON object representing the response
   * @return A new DAPResponse instance
   */
  public static DAPResponse fromJson(JsonObject obj) {
    DAPResponse response = new DAPResponse();
    response.requestSeq = obj.get("request_seq").getAsInt();
    response.success = obj.get("success").getAsBoolean();
    response.command = obj.get("command").getAsString();
    
    if (obj.has("message") && !obj.get("message").isJsonNull()) {
      response.message = obj.get("message").getAsString();
    }
    
    if (obj.has("body") && !obj.get("body").isJsonNull()) {
      response.body = obj.getAsJsonObject("body");
    }
    
    return response;
  }

  /**
   * Checks if the response indicates success.
   *
   * @return true if successful, false otherwise
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * Gets the sequence number of the request this response is for.
   *
   * @return The request sequence number
   */
  public int getRequestSeq() {
    return requestSeq;
  }

  /**
   * Gets the command name.
   *
   * @return The command name
   */
  public String getCommand() {
    return command;
  }

  /**
   * Gets the message, if present.
   *
   * @return The message, or null if not present
   */
  public String getMessage() {
    return message;
  }

  /**
   * Gets a string value from the response body.
   *
   * @param key          The body field key
   * @param defaultValue The default value if field is absent or null
   * @return The string value or defaultValue
   */
  public String getBodyString(String key, String defaultValue) {
    if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
      return defaultValue;
    }
    return body.get(key).getAsString();
  }

  /**
   * Gets an integer value from the response body.
   *
   * @param key          The body field key
   * @param defaultValue The default value if field is absent or null
   * @return The integer value or defaultValue
   */
  public int getBodyInt(String key, int defaultValue) {
    if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
      return defaultValue;
    }
    return body.get(key).getAsInt();
  }

  /**
   * Gets a boolean value from the response body.
   *
   * @param key          The body field key
   * @param defaultValue The default value if field is absent or null
   * @return The boolean value or defaultValue
   */
  public boolean getBodyBool(String key, boolean defaultValue) {
    if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
      return defaultValue;
    }
    return body.get(key).getAsBoolean();
  }

  /**
   * Gets a raw deserialized value from the response body.
   *
   * @param key The body field key
   * @return The deserialized object, or null if absent
   */
  public Object getBody(String key) {
    if (body == null || !body.has(key) || body.get(key).isJsonNull()) {
      return null;
    }
    return new Gson().fromJson(body.get(key), Object.class);
  }

  /**
   * Gets an array field from the response body as a list of maps.
   *
   * @param key The body field key
   * @return List of maps representing array elements, or empty list if absent
   */
  public List<Map<String, Object>> getBodyList(String key) {
    if (body == null || !body.has(key)) {
      return Collections.emptyList();
    }
    return new Gson().fromJson(body.get(key), new TypeToken<List<Map<String, Object>>>(){}.getType());
  }

  @Override
  public JsonObject toJson() {
    JsonObject obj = new JsonObject();
    obj.addProperty("type", "response");
    obj.addProperty("request_seq", requestSeq);
    obj.addProperty("success", success);
    obj.addProperty("command", command);
    
    if (message != null) {
      obj.addProperty("message", message);
    }
    
    if (body != null) {
      obj.add("body", body);
    }
    
    return obj;
  }
}
