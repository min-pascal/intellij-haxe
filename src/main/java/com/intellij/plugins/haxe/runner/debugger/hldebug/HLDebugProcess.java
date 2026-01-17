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

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.*;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Debug process for HashLink applications using DAP (Debug Adapter Protocol).
 * 
 * This class manages the debugging session for HashLink (.hl) applications.
 * It communicates with the HashLink debug adapter via DAP over stdin/stdout.
 */
public class HLDebugProcess extends XDebugProcess implements HLDebugProcessInterface {

    private static final Logger LOG = Logger.getInstance(HLDebugProcess.class);

    private final ExecutionResult executionResult;
    private final String hlExecutablePath;
    private final String programPath;
    private final String workingDirectory;
    private final int debugPort;
    private final List<String> classPaths;

    private final XBreakpointHandler<?>[] breakpointHandlers;
    private final Map<String, List<XLineBreakpoint<?>>> pendingBreakpoints = new ConcurrentHashMap<>();
    private final Map<Integer, XLineBreakpoint<?>> breakpointIdMap = new ConcurrentHashMap<>();

    private DAPClient dapClient;
    private Process adapterProcess;
    private volatile boolean isConnected = false;
    private volatile int currentThreadId = 1;

    public HLDebugProcess(@NotNull XDebugSession session,
                          @NotNull ExecutionResult executionResult,
                          @NotNull String hlExecutablePath,
                          @NotNull String programPath,
                          @NotNull String workingDirectory,
                          int debugPort) {
        this(session, executionResult, hlExecutablePath, programPath, workingDirectory, debugPort, Collections.emptyList());
    }

    public HLDebugProcess(@NotNull XDebugSession session,
                          @NotNull ExecutionResult executionResult,
                          @NotNull String hlExecutablePath,
                          @NotNull String programPath,
                          @NotNull String workingDirectory,
                          int debugPort,
                          @NotNull List<String> classPaths) {
        super(session);
        this.executionResult = executionResult;
        this.hlExecutablePath = hlExecutablePath;
        this.programPath = programPath;
        this.workingDirectory = workingDirectory;
        this.debugPort = debugPort;
        this.classPaths = new ArrayList<>(classPaths);

        this.breakpointHandlers = createBreakpointHandlers();
        
        // Add listener to detect when HL process terminates
        ProcessHandler processHandler = executionResult.getProcessHandler();
        if (processHandler != null) {
            processHandler.addProcessListener(new com.intellij.execution.process.ProcessAdapter() {
                @Override
                public void processTerminated(@NotNull com.intellij.execution.process.ProcessEvent event) {
                    LOG.info("HL process terminated with exit code: " + event.getExitCode());
                    handleHLProcessTerminated(event.getExitCode());
                }
            });
        }
    }
    
    private void handleHLProcessTerminated(int exitCode) {
        LOG.info("Handling HL process termination, exitCode=" + exitCode);
        isConnected = false;
        
        // Clean up adapter process
        if (adapterProcess != null && adapterProcess.isAlive()) {
            adapterProcess.destroyForcibly();
        }
        
        if (dapClient != null) {
            dapClient.stop();
        }
        
        // Stop the debug session
        ApplicationManager.getApplication().invokeLater(() -> {
            XDebugSession session = getSession();
            if (session != null && !session.isStopped()) {
                session.stop();
            }
        });
    }

    // ==================== Lifecycle ====================

    @Override
    public void sessionInitialized() {
        super.sessionInitialized();
        
        LOG.info("HashLink debug session initialized");
        LOG.info("HL executable: " + hlExecutablePath);
        LOG.info("Program: " + programPath);
        LOG.info("Working directory: " + workingDirectory);
        LOG.info("Debug port: " + debugPort);
        
        // On macOS, the HashLink debugger has issues with native debugging APIs
        // (Mach debugging) that cause it to hang. For now, just run without breakpoint support.
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            LOG.warn("macOS detected - HashLink debugging is limited due to Mach API issues");
            markBreakpointsInvalid("macOS debugging not fully supported");
            return;
        }
        
        // Start the debug adapter asynchronously on other platforms
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                startDebugAdapter();
            } catch (Exception e) {
                LOG.error("Failed to start debug adapter", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    getSession().reportError("Failed to start HashLink debug adapter: " + e.getMessage());
                });
            }
        });
    }

    private void startDebugAdapter() throws Exception {
        // Find the adapter
        String adapterPath = findAdapter();
        
        if (adapterPath == null) {
            LOG.warn("HashLink debug adapter not found - running without debug protocol");
            markBreakpointsInvalid("Debug adapter not found");
            return;
        }

        LOG.info("Starting debug adapter: " + adapterPath);

        // Start the adapter process
        ProcessBuilder pb = new ProcessBuilder();
        
        // Set working directory - use adapter's directory if workingDirectory is empty
        File workDir;
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            workDir = new File(workingDirectory);
        } else {
            workDir = new File(adapterPath).getParentFile();
        }
        if (workDir != null && workDir.exists()) {
            pb.directory(workDir);
        }
        LOG.info("Working directory: " + (workDir != null ? workDir.getAbsolutePath() : "null"));
        
        if (adapterPath.endsWith(".js")) {
            // Node.js adapter - find node executable
            String nodePath = findNodeExecutable();
            if (nodePath == null) {
                throw new IOException("Node.js not found. Please install Node.js or add it to your PATH.");
            }
            LOG.info("Using Node.js: " + nodePath);
            pb.command(nodePath, adapterPath);
        } else if (adapterPath.endsWith(".hl")) {
            // HashLink adapter
            pb.command(hlExecutablePath, adapterPath);
        } else {
            throw new IOException("Unknown adapter type: " + adapterPath);
        }

        // Merge stderr into stdout - DAP uses stdout for messages
        pb.redirectErrorStream(true);
        
        // Inherit environment but ensure correct encoding
        pb.environment().put("NODE_OPTIONS", "--no-warnings");
        
        adapterProcess = pb.start();
        
        LOG.info("Adapter process started, PID: " + adapterProcess.pid());

        // Create DAP client
        dapClient = new DAPClient(
            adapterProcess.getInputStream(),
            adapterProcess.getOutputStream(),
            this::handleDAPEvent
        );
        dapClient.start();

        // Give the adapter a moment to start up
        Thread.sleep(100);
        
        // Check if process is still alive
        if (!adapterProcess.isAlive()) {
            int exitCode = adapterProcess.exitValue();
            throw new IOException("Debug adapter process exited immediately with code: " + exitCode);
        }

        // Initialize DAP session
        initializeDAPSession();
    }

    @Nullable
    private String findAdapter() {
        // Check various locations for the adapter
        String[] searchPaths = {
            // Common development locations
            System.getProperty("user.home") + "/Documents/GIT2/hashlink-debugger/adapter.js",
            System.getProperty("user.home") + "/hashlink-debugger/adapter.js",
            // In the plugin resources
            System.getProperty("user.home") + "/.haxe/hashlink-debugger/adapter.js",
            // In the workspace
            workingDirectory + "/hashlink-debugger/adapter.js",
            // Relative to the HL file (for development)
            new File(programPath).getParent() + "/hashlink-debugger/adapter.js",
            // VS Code extension location (common on macOS)
            System.getProperty("user.home") + "/.vscode/extensions/haxefoundation.haxe-hl-1.4.34/adapter.js",
        };

        for (String path : searchPaths) {
            File file = new File(path);
            if (file.exists()) {
                LOG.info("Found adapter at: " + path);
                return path;
            }
        }

        LOG.warn("HashLink debug adapter not found in any of the search paths");
        return null;
    }

    @Nullable
    private String findNodeExecutable() {
        // For HashLink debugging on Apple Silicon, we need x86_64 Node.js
        // because HashLink JIT only runs as x86_64 (via Rosetta)
        // Check Intel Homebrew Cellar first for x86_64 node
        String[] nodePaths = {
            // Intel Homebrew Cellar (x86_64) - preferred for HL debugging on Apple Silicon
            "/usr/local/Cellar/node/25.2.1/bin/node",
            "/usr/local/Cellar/node/22.15.0/bin/node",
            // Homebrew on Intel Mac (linked)
            "/usr/local/bin/node",
            // Homebrew on Apple Silicon (arm64 - won't work with x86_64 HL)
            "/opt/homebrew/bin/node",
            // NVM default location
            System.getProperty("user.home") + "/.nvm/versions/node/v22.15.0/bin/node",
            System.getProperty("user.home") + "/.nvm/current/bin/node",
            // System node
            "/usr/bin/node",
            // Volta
            System.getProperty("user.home") + "/.volta/bin/node",
        };

        for (String path : nodePaths) {
            File file = new File(path);
            if (file.exists() && file.canExecute()) {
                return path;
            }
        }

        // Try to find node in PATH using 'which'
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "which node");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream())
            );
            String line = reader.readLine();
            p.waitFor();
            
            if (line != null && !line.isEmpty() && new File(line).exists()) {
                return line;
            }
        } catch (Exception e) {
            LOG.warn("Could not find node using 'which'", e);
        }

        return null;
    }

    private void initializeDAPSession() throws Exception {
        LOG.info("Initializing DAP session...");
        
        // Send initialize request
        DAPRequest initRequest = new DAPRequest("initialize");
        initRequest.setArgument("clientID", "intellij-haxe");
        initRequest.setArgument("clientName", "IntelliJ IDEA Haxe Plugin");
        initRequest.setArgument("adapterID", "hashlink");
        initRequest.setArgument("pathFormat", "path");  // Required: use native OS paths
        initRequest.setArgument("linesStartAt1", true);
        initRequest.setArgument("columnsStartAt1", true);
        initRequest.setArgument("supportsVariableType", true);
        initRequest.setArgument("supportsVariablePaging", false);
        initRequest.setArgument("supportsRunInTerminalRequest", false);

        LOG.info("Sending initialize request...");
        CompletableFuture<DAPResponse> future = dapClient.sendRequest(initRequest);
        
        try {
            // Give the adapter plenty of time to initialize (especially on first run or under Rosetta)
            DAPResponse response = future.get(30, TimeUnit.SECONDS);
            LOG.info("Received initialize response: success=" + response.isSuccess());

            if (!response.isSuccess()) {
                throw new Exception("Failed to initialize DAP session: " + response.getMessage());
            }
        } catch (java.util.concurrent.TimeoutException e) {
            // Check if process is still alive
            if (adapterProcess != null && !adapterProcess.isAlive()) {
                throw new Exception("Debug adapter process died (exit code: " + adapterProcess.exitValue() + ")");
            }
            throw new Exception("Timeout waiting for DAP adapter response");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new Exception("Error from DAP adapter: " + e.getCause().getMessage());
        }

        isConnected = true;
        LOG.info("DAP session initialized");

        // Send launch/attach request
        launchTarget();
    }

    private void launchTarget() throws Exception {
        // Build classPaths - at minimum, include the working directory and program directory
        List<String> effectiveClassPaths = new ArrayList<>(classPaths);
        
        // Determine effective working directory - use program's parent if not specified
        String effectiveCwd = workingDirectory;
        if (effectiveCwd == null || effectiveCwd.isEmpty()) {
            File programFile = new File(programPath);
            if (programFile.getParentFile() != null) {
                // Go up from Export/hl/bin to project root if possible
                File parent = programFile.getParentFile();
                // Try to find project root (look for src folder)
                for (int i = 0; i < 4 && parent != null; i++) {
                    File srcDir = new File(parent, "src");
                    if (srcDir.exists() && srcDir.isDirectory()) {
                        effectiveCwd = parent.getAbsolutePath();
                        break;
                    }
                    parent = parent.getParentFile();
                }
                // Fallback to program's directory
                if (effectiveCwd == null || effectiveCwd.isEmpty()) {
                    effectiveCwd = programFile.getParent();
                }
            }
        }
        LOG.info("Effective CWD: " + effectiveCwd);
        
        if (effectiveClassPaths.isEmpty()) {
            // Add default paths
            if (effectiveCwd != null && !effectiveCwd.isEmpty()) {
                effectiveClassPaths.add(effectiveCwd);
                // Add a "src" subdirectory if it exists (common convention)
                File srcDir = new File(effectiveCwd, "src");
                if (srcDir.exists()) {
                    effectiveClassPaths.add(srcDir.getAbsolutePath());
                }
            }
            // Add the directory containing the .hl file
            File programFile = new File(programPath);
            if (programFile.getParent() != null) {
                effectiveClassPaths.add(programFile.getParent());
            }
        }
        
        LOG.info("Using classPaths: " + effectiveClassPaths);
        
        // Use launch request - the adapter will spawn HL itself
        // This avoids PID protocol mismatch issues between HL versions and the adapter
        DAPRequest launchRequest = new DAPRequest("launch");
        launchRequest.setArgument("program", programPath);
        launchRequest.setArgument("hl", hlExecutablePath);
        launchRequest.setArgument("cwd", effectiveCwd);
        launchRequest.setArgument("port", debugPort);
        launchRequest.setArgument("classPaths", effectiveClassPaths);

        DAPResponse response;
        try {
            response = dapClient.sendRequest(launchRequest).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // The debugger is likely hanging on ptrace/Mach debugging API
            throw new Exception("Launch request timed out. On macOS, this usually means the native debugger " +
                "cannot attach to the HashLink process due to macOS security restrictions.\n\n" +
                "The HashLink debugger requires ptrace/Mach debugging APIs which need special entitlements.\n" +
                "Try signing Node.js with debugger entitlements.");
        }

        if (!response.isSuccess()) {
            String launchError = response.getMessage();
            LOG.warn("Launch failed: " + launchError);
            
            // Check if it's a debugger initialization failure
            if (launchError != null && (launchError.contains("Failed to initialize debugger") || 
                                        launchError.contains("timeout") ||
                                        launchError.isEmpty())) {
                throw new Exception("Failed to launch debugger. On macOS, the HashLink debugger requires " +
                    "special permissions to attach to processes. This often fails due to:\n" +
                    "1. Architecture mismatch (HL is x86_64 but debug tools are arm64)\n" +
                    "2. macOS code-signing restrictions (the Node.js binary needs debugger entitlements)\n" +
                    "3. System Integrity Protection (SIP) blocking ptrace access\n\n" +
                    "Original error: " + launchError);
            }
            throw new Exception("Failed to launch: " + launchError);
        }

        LOG.info("Target launched");

        // Send pending breakpoints
        sendPendingBreakpoints();

        // Send configurationDone
        DAPRequest configDoneRequest = new DAPRequest("configurationDone");
        dapClient.sendRequest(configDoneRequest).get(10, TimeUnit.SECONDS);

        LOG.info("Configuration done");
    }

    @Override
    public void stop() {
        LOG.info("Stopping HashLink debug session");

        // Send disconnect to adapter
        if (dapClient != null && isConnected) {
            try {
                DAPRequest disconnectRequest = new DAPRequest("disconnect");
                disconnectRequest.setArgument("terminateDebuggee", true);
                dapClient.sendRequest(disconnectRequest);
                Thread.sleep(100); // Give it a moment to send
            } catch (Exception e) {
                LOG.warn("Error sending disconnect request", e);
            }
            dapClient.stop();
        }

        // Kill adapter process
        if (adapterProcess != null && adapterProcess.isAlive()) {
            adapterProcess.destroyForcibly();
        }

        // Kill HL process
        ProcessHandler processHandler = doGetProcessHandler();
        if (processHandler != null && !processHandler.isProcessTerminated()) {
            LOG.info("Destroying HL process");
            processHandler.destroyProcess();
        }

        isConnected = false;
    }

    // ==================== Process Handler ====================

    @Nullable
    @Override
    protected ProcessHandler doGetProcessHandler() {
        return executionResult != null ? executionResult.getProcessHandler() : null;
    }

    @NotNull
    @Override
    public ExecutionConsole createConsole() {
        return executionResult != null ? executionResult.getExecutionConsole() : super.createConsole();
    }

    // ==================== Breakpoints ====================

    @NotNull
    @Override
    public XBreakpointHandler<?>[] getBreakpointHandlers() {
        return breakpointHandlers;
    }

    private XBreakpointHandler<?>[] createBreakpointHandlers() {
        return new XBreakpointHandler<?>[]
            {
                // Handle HashLink-specific breakpoints
                new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<?>>>
                    (HLBreakpointType.class) {
                    public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint) {
                        HLDebugProcess.this.registerBreakpoint(breakpoint);
                    }

                    public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint, boolean temporary) {
                        HLDebugProcess.this.unregisterBreakpoint(breakpoint);
                    }
                },
                // Also handle regular Haxe breakpoints
                new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>>
                    (com.intellij.plugins.haxe.runner.debugger.HaxeBreakpointType.class) {
                    public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
                        HLDebugProcess.this.registerBreakpoint(breakpoint);
                    }

                    public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint, boolean temporary) {
                        HLDebugProcess.this.unregisterBreakpoint(breakpoint);
                    }
                }
            };
    }

    private void registerBreakpoint(@NotNull XLineBreakpoint<?> breakpoint) {
        XSourcePosition position = breakpoint.getSourcePosition();
        if (position == null || position.getFile() == null) {
            return;
        }

        String filePath = position.getFile().getPath();
        int line = position.getLine() + 1; // Convert to 1-based

        LOG.info("Registering breakpoint: " + filePath + ":" + line);

        // Add to pending
        pendingBreakpoints.computeIfAbsent(filePath, k -> new ArrayList<>()).add(breakpoint);

        if (isConnected) {
            sendBreakpointsForFile(filePath);
        } else {
            getSession().setBreakpointInvalid(breakpoint, "Debug session not connected");
        }
    }

    private void unregisterBreakpoint(@NotNull XLineBreakpoint<?> breakpoint) {
        XSourcePosition position = breakpoint.getSourcePosition();
        if (position == null || position.getFile() == null) {
            return;
        }

        String filePath = position.getFile().getPath();
        LOG.info("Unregistering breakpoint: " + filePath);

        List<XLineBreakpoint<?>> pending = pendingBreakpoints.get(filePath);
        if (pending != null) {
            pending.remove(breakpoint);
        }

        if (isConnected) {
            sendBreakpointsForFile(filePath);
        }
    }

    private void sendPendingBreakpoints() {
        for (String filePath : pendingBreakpoints.keySet()) {
            sendBreakpointsForFile(filePath);
        }
    }

    private void sendBreakpointsForFile(String filePath) {
        if (!isConnected || dapClient == null) return;

        List<XLineBreakpoint<?>> breakpoints = pendingBreakpoints.getOrDefault(filePath, Collections.emptyList());

        DAPRequest request = new DAPRequest("setBreakpoints");

        Map<String, Object> source = new HashMap<>();
        source.put("path", filePath);
        request.setArgument("source", source);

        List<Map<String, Object>> bpList = new ArrayList<>();
        for (XLineBreakpoint<?> bp : breakpoints) {
            XSourcePosition pos = bp.getSourcePosition();
            if (pos != null) {
                Map<String, Object> bpData = new HashMap<>();
                bpData.put("line", pos.getLine() + 1);

                String condition = bp.getConditionExpression() != null ?
                    bp.getConditionExpression().getExpression() : null;
                if (condition != null && !condition.isEmpty()) {
                    bpData.put("condition", condition);
                }

                bpList.add(bpData);
            }
        }
        request.setArgument("breakpoints", bpList);

        try {
            dapClient.sendRequest(request).thenAccept(response -> {
                if (response.isSuccess()) {
                    List<Map<String, Object>> verifiedBps = response.getBody("breakpoints");
                    if (verifiedBps != null) {
                        for (int i = 0; i < verifiedBps.size() && i < breakpoints.size(); i++) {
                            Map<String, Object> verified = verifiedBps.get(i);
                            XLineBreakpoint<?> bp = breakpoints.get(i);
                            boolean isVerified = Boolean.TRUE.equals(verified.get("verified"));

                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (isVerified) {
                                    getSession().setBreakpointVerified(bp);
                                } else {
                                    String message = (String) verified.getOrDefault("message", "Could not set breakpoint");
                                    getSession().setBreakpointInvalid(bp, message);
                                }
                            });
                        }
                    }
                } else {
                    LOG.warn("Failed to set breakpoints: " + response.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.error("Error sending breakpoints", e);
        }
    }

    private void markBreakpointsInvalid(String message) {
        for (List<XLineBreakpoint<?>> breakpoints : pendingBreakpoints.values()) {
            for (XLineBreakpoint<?> bp : breakpoints) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    getSession().setBreakpointInvalid(bp, message);
                });
            }
        }
    }

    // ==================== Execution Control ====================

    @Override
    public void startPausing() {
        if (!isConnected) return;

        DAPRequest request = new DAPRequest("pause");
        request.setArgument("threadId", currentThreadId);

        try {
            dapClient.sendRequest(request);
        } catch (Exception e) {
            LOG.error("Error pausing execution", e);
        }
    }

    @Override
    public void resume(@Nullable XSuspendContext context) {
        if (!isConnected) return;

        int threadId = getThreadIdFromContext(context);

        DAPRequest request = new DAPRequest("continue");
        request.setArgument("threadId", threadId);

        try {
            dapClient.sendRequest(request);
        } catch (Exception e) {
            LOG.error("Error resuming execution", e);
        }
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        if (!isConnected) return;

        int threadId = getThreadIdFromContext(context);

        DAPRequest request = new DAPRequest("next");
        request.setArgument("threadId", threadId);

        try {
            dapClient.sendRequest(request);
        } catch (Exception e) {
            LOG.error("Error stepping over", e);
        }
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        if (!isConnected) return;

        int threadId = getThreadIdFromContext(context);

        DAPRequest request = new DAPRequest("stepIn");
        request.setArgument("threadId", threadId);

        try {
            dapClient.sendRequest(request);
        } catch (Exception e) {
            LOG.error("Error stepping into", e);
        }
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        if (!isConnected) return;

        int threadId = getThreadIdFromContext(context);

        DAPRequest request = new DAPRequest("stepOut");
        request.setArgument("threadId", threadId);

        try {
            dapClient.sendRequest(request);
        } catch (Exception e) {
            LOG.error("Error stepping out", e);
        }
    }

    private int getThreadIdFromContext(@Nullable XSuspendContext context) {
        // TODO: Extract thread ID from context when we implement HLSuspendContext
        return currentThreadId;
    }

    // ==================== DAP Event Handling ====================

    private void handleDAPEvent(DAPEvent event) {
        LOG.info("Received DAP event: " + event.getEvent());

        switch (event.getEvent()) {
            case "stopped":
                handleStoppedEvent(event);
                break;
            case "continued":
                handleContinuedEvent(event);
                break;
            case "terminated":
            case "exited":
                handleTerminatedEvent(event);
                break;
            case "thread":
                handleThreadEvent(event);
                break;
            case "output":
                handleOutputEvent(event);
                break;
            case "breakpoint":
                handleBreakpointEvent(event);
                break;
            default:
                LOG.debug("Unhandled DAP event: " + event.getEvent());
        }
    }

    private void handleStoppedEvent(DAPEvent event) {
        int threadId = event.getBodyInt("threadId", 1);
        String reason = event.getBodyString("reason", "breakpoint");

        currentThreadId = threadId;
        LOG.info("Stopped: thread=" + threadId + ", reason=" + reason);

        // Request stack trace and create suspend context
        requestStackTrace(threadId, reason);
    }

    private void requestStackTrace(int threadId, String stopReason) {
        DAPRequest request = new DAPRequest("stackTrace");
        request.setArgument("threadId", threadId);
        request.setArgument("startFrame", 0);
        request.setArgument("levels", 50);

        try {
            dapClient.sendRequest(request).thenAccept(response -> {
                if (response.isSuccess()) {
                    List<Map<String, Object>> stackFrames = response.getBody("stackFrames");
                    
                    // Create suspend context and notify session
                    HLSuspendContext suspendContext = new HLSuspendContext(
                        this, threadId, stackFrames, stopReason
                    );

                    ApplicationManager.getApplication().invokeLater(() -> {
                        getSession().positionReached(suspendContext);
                    });
                } else {
                    LOG.warn("Failed to get stack trace: " + response.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.error("Error requesting stack trace", e);
        }
    }

    private void handleContinuedEvent(DAPEvent event) {
        LOG.info("Continued");
    }

    private void handleTerminatedEvent(DAPEvent event) {
        LOG.info("Terminated");
        ApplicationManager.getApplication().invokeLater(() -> {
            getSession().stop();
        });
    }

    private void handleThreadEvent(DAPEvent event) {
        String reason = event.getBodyString("reason", "");
        int threadId = event.getBodyInt("threadId", -1);
        LOG.info("Thread " + threadId + " " + reason);
    }

    private void handleOutputEvent(DAPEvent event) {
        String output = event.getBodyString("output", "");
        String category = event.getBodyString("category", "console");

        if (!output.isEmpty()) {
            ProcessHandler handler = doGetProcessHandler();
            if (handler != null) {
                handler.notifyTextAvailable(output,
                    "stderr".equals(category) ?
                        com.intellij.execution.process.ProcessOutputTypes.STDERR :
                        com.intellij.execution.process.ProcessOutputTypes.STDOUT
                );
            }
        }
    }

    private void handleBreakpointEvent(DAPEvent event) {
        String reason = event.getBodyString("reason", "");
        Map<String, Object> breakpoint = event.getBody("breakpoint");

        if (breakpoint != null) {
            int id = ((Number) breakpoint.getOrDefault("id", -1)).intValue();
            boolean verified = Boolean.TRUE.equals(breakpoint.get("verified"));

            XLineBreakpoint<?> bp = breakpointIdMap.get(id);
            if (bp != null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (verified) {
                        getSession().setBreakpointVerified(bp);
                    } else {
                        String message = (String) breakpoint.getOrDefault("message", "Breakpoint not verified");
                        getSession().setBreakpointInvalid(bp, message);
                    }
                });
            }
        }
    }

    // ==================== DAP Helpers for Stack/Variables ====================

    @Override
    public int getCurrentThreadId() {
        return currentThreadId;
    }

    @Override
    @Nullable
    public DAPClient getDapClient() {
        return dapClient;
    }

    @Override
    public CompletableFuture<DAPResponse> getScopes(int frameId) {
        if (!isConnected) return CompletableFuture.completedFuture(null);
        
        DAPRequest request = new DAPRequest("scopes");
        request.setArgument("frameId", frameId);
        return dapClient.sendRequest(request);
    }

    @Override
    public CompletableFuture<DAPResponse> getVariables(int variablesReference) {
        if (!isConnected) return CompletableFuture.completedFuture(null);
        
        DAPRequest request = new DAPRequest("variables");
        request.setArgument("variablesReference", variablesReference);
        return dapClient.sendRequest(request);
    }

    @Override
    public CompletableFuture<DAPResponse> evaluate(String expression, int frameId, String context) {
        if (!isConnected) return CompletableFuture.completedFuture(null);
        
        DAPRequest request = new DAPRequest("evaluate");
        request.setArgument("expression", expression);
        request.setArgument("frameId", frameId);
        request.setArgument("context", context != null ? context : "watch");
        return dapClient.sendRequest(request);
    }

    // ==================== Editors Provider ====================

    @NotNull
    @Override
    public XDebuggerEditorsProvider getEditorsProvider() {
        return new HLDebuggerEditorsProvider();
    }
}
