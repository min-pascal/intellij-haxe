package com.intellij.plugins.haxe.runner.debugger.hldebug.dap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a DAP event message received from the adapter.
 * Provides typed accessor methods for common body fields.
 */
public class DAPEvent extends DAPMessage {

  private String event;
  private JsonObject body;

  /**
   * Private constructor used by fromJson factory method.
   */
  private DAPEvent() {
  }

  /**
   * Creates a DAPEvent from a parsed JSON object.
   *
   * @param obj The JSON object representing the event
   * @return A new DAPEvent instance
   */
  public static DAPEvent fromJson(JsonObject obj) {
    DAPEvent event = new DAPEvent();
    event.event = obj.get("event").getAsString();
    
    if (obj.has("body") && !obj.get("body").isJsonNull()) {
      event.body = obj.getAsJsonObject("body");
    }
    
    return event;
  }

  /**
   * Gets the event name.
   *
   * @return The event name
   */
  public String getEvent() {
    return event;
  }

  /**
   * Gets a string value from the event body.
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
   * Gets an integer value from the event body.
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
   * Gets a boolean value from the event body.
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
   * Gets a raw deserialized value from the event body.
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
   * Gets an array field from the event body as a list of maps.
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
    obj.addProperty("type", "event");
    obj.addProperty("event", event);
    
    if (body != null) {
      obj.add("body", body);
    }
    
    return obj;
  }
}
