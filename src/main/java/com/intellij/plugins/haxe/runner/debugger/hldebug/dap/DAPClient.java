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
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * DAP (Debug Adapter Protocol) client for communicating with debug adapters.
 * 
 * This client communicates with a debug adapter process via stdin/stdout using
 * the DAP wire protocol (Content-Length headers + JSON body).
 */
public class DAPClient {
    private static final Logger LOG = Logger.getInstance(DAPClient.class);
    
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Consumer<DAPEvent> eventHandler;
    
    private final AtomicInteger sequenceNumber = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<DAPResponse>> pendingRequests = new ConcurrentHashMap<>();
    
    private volatile boolean running = false;
    private Thread readerThread;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public DAPClient(@NotNull InputStream inputStream,
                     @NotNull OutputStream outputStream,
                     @NotNull Consumer<DAPEvent> eventHandler) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.eventHandler = eventHandler;
    }
    
    /**
     * Start the client - begins reading messages from the adapter.
     */
    public void start() {
        if (running) return;
        
        running = true;
        readerThread = new Thread(this::readLoop, "DAP-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
        
        LOG.info("DAP client started");
    }
    
    /**
     * Stop the client.
     */
    public void stop() {
        running = false;
        
        if (readerThread != null) {
            readerThread.interrupt();
        }
        
        executor.shutdown();
        
        // Complete all pending requests with an error
        for (CompletableFuture<DAPResponse> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("DAP client stopped"));
        }
        pendingRequests.clear();
        
        LOG.info("DAP client stopped");
    }
    
    /**
     * Send a request and return a future for the response.
     */
    public CompletableFuture<DAPResponse> sendRequest(@NotNull DAPRequest request) {
        int seq = sequenceNumber.getAndIncrement();
        request.setSeq(seq);
        
        CompletableFuture<DAPResponse> future = new CompletableFuture<>();
        pendingRequests.put(seq, future);
        
        try {
            sendMessage(request);
            LOG.debug("Sent request: " + request.getCommand() + " (seq=" + seq + ")");
        } catch (IOException e) {
            pendingRequests.remove(seq);
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Send a message to the adapter.
     */
    private synchronized void sendMessage(@NotNull DAPMessage message) throws IOException {
        String encoded = message.encode();
        LOG.info("Sending DAP message: " + encoded.substring(0, Math.min(200, encoded.length())));
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        outputStream.write(bytes);
        outputStream.flush();
        LOG.info("DAP message sent successfully");
    }
    
    /**
     * Main read loop - runs in a separate thread.
     */
    private void readLoop() {
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        LOG.info("DAP reader loop started");
        
        try {
            while (running) {
                String message = readMessage(bis);
                if (message != null) {
                    LOG.debug("Received message: " + message.substring(0, Math.min(200, message.length())));
                    processMessage(message);
                } else {
                    LOG.info("readMessage returned null, adapter may have closed");
                    break;
                }
            }
        } catch (IOException e) {
            if (running) {
                LOG.warn("Error reading from DAP adapter: " + e.getMessage(), e);
            }
        }
        
        LOG.info("DAP reader loop ended");
        // If we're still supposed to be running, complete pending requests with error
        if (running) {
            running = false;
            for (CompletableFuture<DAPResponse> future : pendingRequests.values()) {
                future.completeExceptionally(new IOException("DAP adapter closed unexpectedly"));
            }
            pendingRequests.clear();
        }
    }
    
    /**
     * Read a single DAP message from the stream.
     */
    private String readMessage(BufferedInputStream bis) throws IOException {
        // Read headers
        StringBuilder headerBuilder = new StringBuilder();
        int contentLength = -1;
        
        LOG.info("Waiting for DAP message...");
        
        while (true) {
            String line = readLine(bis);
            LOG.info("Read header line: '" + line + "'");
            if (line == null) {
                LOG.info("readLine returned null (EOF)");
                return null; // EOF
            }
            
            if (line.isEmpty()) {
                // End of headers
                break;
            }
            
            if (line.startsWith("Content-Length:")) {
                String lengthStr = line.substring("Content-Length:".length()).trim();
                contentLength = Integer.parseInt(lengthStr);
            }
        }
        
        if (contentLength < 0) {
            throw new IOException("Missing Content-Length header");
        }
        
        // Read body
        byte[] body = new byte[contentLength];
        int bytesRead = 0;
        while (bytesRead < contentLength) {
            int read = bis.read(body, bytesRead, contentLength - bytesRead);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading message body");
            }
            bytesRead += read;
        }
        
        return new String(body, StandardCharsets.UTF_8);
    }
    
    /**
     * Read a line (terminated by \r\n) from the stream.
     */
    private String readLine(BufferedInputStream bis) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        
        while (true) {
            int ch = bis.read();
            if (ch < 0) {
                LOG.info("readLine: EOF reached, buffer so far: '" + sb + "'");
                return sb.length() > 0 ? sb.toString() : null;
            }
            
            if (prev == '\r' && ch == '\n') {
                // Remove the trailing \r
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                }
                LOG.debug("readLine: returning '" + sb + "'");
                return sb.toString();
            }
            
            sb.append((char) ch);
            prev = ch;
            
            // Safety check - log if we've read a lot without finding CRLF
            if (sb.length() > 1000) {
                LOG.warn("readLine: Long line without CRLF: " + sb.substring(0, 100) + "...");
            }
        }
    }
    
    /**
     * Process a received message.
     */
    private void processMessage(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String type = obj.get("type").getAsString();
            
            switch (type) {
                case "response":
                    handleResponse(DAPResponse.fromJson(obj));
                    break;
                case "event":
                    handleEvent(DAPEvent.fromJson(obj));
                    break;
                default:
                    LOG.warn("Unknown DAP message type: " + type);
            }
        } catch (Exception e) {
            LOG.error("Error processing DAP message: " + json, e);
        }
    }
    
    /**
     * Handle a response message.
     */
    private void handleResponse(DAPResponse response) {
        int requestSeq = response.getRequestSeq();
        CompletableFuture<DAPResponse> future = pendingRequests.remove(requestSeq);
        
        if (future != null) {
            future.complete(response);
            LOG.debug("Received response for " + response.getCommand() + " (seq=" + requestSeq + ")");
        } else {
            LOG.warn("Received response for unknown request: " + requestSeq);
        }
    }
    
    /**
     * Handle an event message.
     */
    private void handleEvent(DAPEvent event) {
        LOG.debug("Received event: " + event.getEvent());
        
        // Process event asynchronously
        executor.submit(() -> {
            try {
                eventHandler.accept(event);
            } catch (Exception e) {
                LOG.error("Error handling DAP event: " + event.getEvent(), e);
            }
        });
    }
    
    /**
     * Check if the client is running.
     */
    public boolean isRunning() {
        return running;
    }
}
