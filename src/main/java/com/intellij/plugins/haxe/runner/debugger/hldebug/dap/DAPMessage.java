package com.intellij.plugins.haxe.runner.debugger.hldebug.dap;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Abstract base class for all DAP protocol messages.
 * Handles proper UTF-8 byte-length encoding for the Content-Length header.
 */
public abstract class DAPMessage {

  /**
   * Converts this message to its JSON representation.
   *
   * @return JsonObject representing the message
   */
  public abstract JsonObject toJson();

  /**
   * Encodes the message into DAP wire format with proper UTF-8 byte length.
   * Critical: Uses UTF-8 byte length, not character count, to handle non-ASCII characters.
   *
   * @return Wire-formatted string with Content-Length header
   */
  public String encode() {
    String jsonString = toJson().toString();
    int byteLength = jsonString.getBytes(StandardCharsets.UTF_8).length;
    return "Content-Length: " + byteLength + "\r\n\r\n" + jsonString;
  }
}
