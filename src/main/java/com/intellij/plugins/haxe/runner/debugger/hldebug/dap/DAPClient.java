package com.intellij.plugins.haxe.runner.debugger.hldebug.dap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * DAP protocol client managing bidirectional communication with a debug adapter.
 * Handles request/response correlation and event dispatching.
 */
public class DAPClient {

  private static final Logger LOG = Logger.getInstance(DAPClient.class);

  private final InputStream inputStream;
  private final OutputStream outputStream;
  private final Consumer<DAPEvent> eventHandler;
  private final AtomicInteger sequenceNumber = new AtomicInteger(1);
  private final ConcurrentHashMap<Integer, CompletableFuture<DAPResponse>> pendingRequests = new ConcurrentHashMap<>();
  private volatile boolean running = false;
  private Thread readerThread;
  private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();

  /**
   * Creates a new DAP client.
   *
   * @param inputStream  Stream to read messages from the adapter
   * @param outputStream Stream to write messages to the adapter
   * @param eventHandler Handler for events received from the adapter
   */
  public DAPClient(InputStream inputStream, OutputStream outputStream, Consumer<DAPEvent> eventHandler) {
    this.inputStream = inputStream;
    this.outputStream = outputStream;
    this.eventHandler = eventHandler;
  }

  /**
   * Starts the client's reader thread.
   */
  public void start() {
    if (running) {
      return;
    }
    
    running = true;
    readerThread = new Thread(this::readLoop, "DAP-Reader");
    readerThread.setDaemon(true);
    readerThread.start();
  }

  /**
   * Stops the client and cleans up resources.
   */
  public void stop() {
    running = false;
    
    if (readerThread != null) {
      readerThread.interrupt();
    }
    
    eventExecutor.shutdown();
    
    IOException stopException = new IOException("DAP client stopped");
    for (CompletableFuture<DAPResponse> future : pendingRequests.values()) {
      future.completeExceptionally(stopException);
    }
    pendingRequests.clear();
  }

  /**
   * Sends a request and returns a future for the response.
   *
   * @param request The request to send
   * @return A future that completes with the response
   */
  public CompletableFuture<DAPResponse> sendRequest(DAPRequest request) {
    int seq = sequenceNumber.getAndIncrement();
    request.setSeq(seq);
    
    CompletableFuture<DAPResponse> future = new CompletableFuture<>();
    pendingRequests.put(seq, future);
    
    try {
      sendMessage(request);
    } catch (IOException e) {
      pendingRequests.remove(seq);
      future.completeExceptionally(e);
    }
    
    return future;
  }

  /**
   * Sends a message to the adapter.
   *
   * @param message The message to send
   * @throws IOException If an I/O error occurs
   */
  public synchronized void sendMessage(DAPMessage message) throws IOException {
    String wireString = message.encode();
    outputStream.write(wireString.getBytes(StandardCharsets.UTF_8));
    outputStream.flush();
  }

  /**
   * Main read loop that processes incoming messages.
   */
  private void readLoop() {
    BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);
    
    try {
      while (running) {
        // Read Content-Length header
        String headerLine = readLine(bufferedInput);
        if (headerLine == null) {
          // EOF reached
          if (running) {
            LOG.warn("DAP adapter closed connection unexpectedly");
            IOException exception = new IOException("DAP adapter closed unexpectedly");
            for (CompletableFuture<DAPResponse> future : pendingRequests.values()) {
              future.completeExceptionally(exception);
            }
            pendingRequests.clear();
            running = false;
          }
          break;
        }
        
        if (!headerLine.startsWith("Content-Length: ")) {
          LOG.warn("Invalid DAP header: " + headerLine);
          continue;
        }
        
        int contentLength = Integer.parseInt(headerLine.substring(16).trim());
        
        // Read blank line separator
        String blankLine = readLine(bufferedInput);
        if (blankLine == null || !blankLine.isEmpty()) {
          LOG.warn("Expected blank line after Content-Length header");
          break;
        }
        
        // Read content
        byte[] content = new byte[contentLength];
        int bytesRead = 0;
        while (bytesRead < contentLength) {
          int read = bufferedInput.read(content, bytesRead, contentLength - bytesRead);
          if (read == -1) {
            LOG.warn("Unexpected EOF while reading DAP message content");
            IOException eofException = new IOException("Unexpected EOF while reading DAP message content");
            for (CompletableFuture<DAPResponse> future : pendingRequests.values()) {
              future.completeExceptionally(eofException);
            }
            pendingRequests.clear();
            running = false;
            break;
          }
          bytesRead += read;
        }
        
        if (!running) {
          break;
        }
        
        // Parse JSON
        String json = new String(content, StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String type = obj.get("type").getAsString();
        
        if ("response".equals(type)) {
          DAPResponse response = DAPResponse.fromJson(obj);
          CompletableFuture<DAPResponse> future = pendingRequests.remove(response.getRequestSeq());
          if (future != null) {
            future.complete(response);
          }
        } else if ("event".equals(type)) {
          DAPEvent event = DAPEvent.fromJson(obj);
          eventExecutor.submit(() -> eventHandler.accept(event));
        }
      }
    } catch (IOException e) {
      if (running) {
        LOG.error("Error in DAP read loop", e);
        IOException exception = new IOException("DAP read error: " + e.getMessage(), e);
        for (CompletableFuture<DAPResponse> future : pendingRequests.values()) {
          future.completeExceptionally(exception);
        }
        pendingRequests.clear();
        running = false;
      }
      // If not running, this is expected shutdown - exit silently
    }
  }

  /**
   * Reads a line terminated by \r\n.
   *
   * @param input The input stream to read from
   * @return The line without the terminator, or null if EOF
   * @throws IOException If an I/O error occurs
   */
  private String readLine(BufferedInputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int prevByte = -1;
    
    while (true) {
      int b = input.read();
      if (b == -1) {
        return null; // EOF
      }
      
      if (prevByte == '\r' && b == '\n') {
        // Found line terminator
        byte[] bytes = buffer.toByteArray();
        // Remove the \r from the buffer
        return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
      }
      
      buffer.write(b);
      prevByte = b;
    }
  }

  /**
   * Checks if the client is currently running.
   *
   * @return true if running, false otherwise
   */
  public boolean isRunning() {
    return running;
  }
}
