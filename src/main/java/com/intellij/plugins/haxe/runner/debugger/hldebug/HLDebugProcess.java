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
import com.intellij.openapi.diagnostic.Logger;
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

import java.util.*;
import java.util.concurrent.*;

/**
 * Debug process for HashLink applications.
 * 
 * This class manages the debugging session for HashLink (.hl) applications.
 * 
 * Architecture:
 * - The HashLink VM is started with --debug <port> flag by HLDebuggerState
 * - The VM listens on that TCP port for debug commands
 * - TODO: Implement HashLink debug protocol client to connect and control execution
 * 
 * Current status: SKELETON
 * - Process launches and output is shown in console
 * - Breakpoints are registered but not yet sent to VM
 * - Step/resume/pause not yet implemented
 */
public class HLDebugProcess extends XDebugProcess {

    private static final Logger LOG = Logger.getInstance(HLDebugProcess.class);

    private final ExecutionResult executionResult;
    private final String hlExecutablePath;
    private final String programPath;
    private final String workingDirectory;
    private final int debugPort;

    private final XBreakpointHandler<?>[] breakpointHandlers;
    private final Map<String, List<XLineBreakpoint<?>>> pendingBreakpoints = new ConcurrentHashMap<>();

    public HLDebugProcess(@NotNull XDebugSession session,
                          @NotNull ExecutionResult executionResult,
                          @NotNull String hlExecutablePath,
                          @NotNull String programPath,
                          @NotNull String workingDirectory,
                          int debugPort) {
        super(session);
        this.executionResult = executionResult;
        this.hlExecutablePath = hlExecutablePath;
        this.programPath = programPath;
        this.workingDirectory = workingDirectory;
        this.debugPort = debugPort;

        this.breakpointHandlers = createBreakpointHandlers();
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
        
        // TODO: Connect to HashLink debug port and implement protocol
        // The HashLink VM is already started by HLDebuggerState with --debug flag
        // We need to connect to localhost:debugPort and send debug commands
        
        // For now, just let the process run
        // Breakpoints won't work until we implement the protocol
    }

    @Override
    public void stop() {
        LOG.info("Stopping HashLink debug session");
        // Process will be terminated by the framework via the process handler
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
                new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<?>>>
                    (HLBreakpointType.class) {
                    public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint) {
                        HLDebugProcess.this.registerBreakpoint(breakpoint);
                    }

                    public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint, boolean temporary) {
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
        
        // Queue the breakpoint
        pendingBreakpoints.computeIfAbsent(filePath, k -> new ArrayList<>()).add(breakpoint);
        
        // TODO: Send breakpoint to HashLink VM via debug protocol
        // For now, mark as not verified since we can't set it
        getSession().setBreakpointInvalid(breakpoint, "HashLink debug protocol not yet implemented");
    }

    private void unregisterBreakpoint(@NotNull XLineBreakpoint<?> breakpoint) {
        XSourcePosition position = breakpoint.getSourcePosition();
        if (position == null || position.getFile() == null) {
            return;
        }
        
        String filePath = position.getFile().getPath();
        
        LOG.info("Unregistering breakpoint: " + filePath);
        
        // Remove from pending
        List<XLineBreakpoint<?>> pending = pendingBreakpoints.get(filePath);
        if (pending != null) {
            pending.remove(breakpoint);
        }
        
        // TODO: Remove breakpoint from HashLink VM
    }

    // ==================== Execution Control ====================

    @Override
    public void startPausing() {
        LOG.info("Pause requested - not yet implemented");
        // TODO: Send pause command to HashLink VM
    }

    @Override
    public void resume(@Nullable XSuspendContext context) {
        LOG.info("Resume requested - not yet implemented");
        // TODO: Send continue command to HashLink VM
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        LOG.info("Step over requested - not yet implemented");
        // TODO: Send step over command to HashLink VM
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        LOG.info("Step into requested - not yet implemented");
        // TODO: Send step into command to HashLink VM
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        LOG.info("Step out requested - not yet implemented");
        // TODO: Send step out command to HashLink VM
    }

    // ==================== Editors Provider ====================

    @NotNull
    @Override
    public XDebuggerEditorsProvider getEditorsProvider() {
        return new HLDebuggerEditorsProvider();
    }
}
