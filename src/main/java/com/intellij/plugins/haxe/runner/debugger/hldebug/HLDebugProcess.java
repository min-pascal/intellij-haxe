package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.HaxeBreakpointType;
import com.intellij.util.EnvironmentUtil;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPClient;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPEvent;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPRequest;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPResponse;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.SuspendPolicy;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class HLDebugProcess extends XDebugProcess implements HLDebugProcessInterface {

  private static final Logger LOG = Logger.getInstance(HLDebugProcess.class);

  private final HLDebugConfig config;
  private final ExecutionResult executionResult;
  private final boolean useLaunchMode;
  private DAPClient dapClient;
  private Process adapterProcess;
  private volatile int currentThreadId = -1;
  private boolean capSupportsConditionalBreakpoints = false;
  private boolean capSupportsLogPoints = false;
  private boolean capSupportsEvaluateForHovers = false;
  private boolean capSupportsSingleThreadExecution = false;
  private final Map<String, List<XLineBreakpoint<?>>> breakpointsByFile = new ConcurrentHashMap<>();
  private volatile boolean sessionConnected = false;
  private volatile ConsoleView consoleView;
  private volatile ProcessHandler adapterProcessHandler;

  public HLDebugProcess(@NotNull XDebugSession session, HLDebugConfig config, ExecutionResult executionResult) {
    this(session, config, executionResult, false);
  }

  /**
   * @param useLaunchMode if true, sends a DAP 'launch' request (adapter spawns HL);
   *                      if false, sends an 'attach' request (expects HL already running).
   */
  public HLDebugProcess(@NotNull XDebugSession session, HLDebugConfig config,
                        ExecutionResult executionResult, boolean useLaunchMode) {
    super(session);
    this.config = config;
    this.executionResult = executionResult;
    this.useLaunchMode = useLaunchMode;
  }

  @Override
  public void sessionInitialized() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        printToConsole("Starting HashLink debug adapter...\n");

        // Spawn adapter
        String nodePath = config.getNodePath();
        String adapterPath = config.getAdapterPath();
        LOG.info("[HL Debug] Spawning adapter: node=" + nodePath + " adapter=" + adapterPath);
        ProcessBuilder pb = new ProcessBuilder(nodePath, adapterPath);
        pb.redirectErrorStream(false);
        // Redirect stderr to file so native (C) debug output is captured
        java.io.File stderrFile = new java.io.File(System.getProperty("java.io.tmpdir"), "hl_adapter_stderr.log");
        pb.redirectError(ProcessBuilder.Redirect.to(stderrFile));
        adapterProcess = pb.start();

        LOG.info("[HL Debug] Adapter process started, PID=" + adapterProcess.pid());
        LOG.info("[HL Debug] Adapter stderr -> " + stderrFile.getAbsolutePath());

        // Create a process handler so the IDE can track the adapter lifecycle
        adapterProcessHandler = new AdapterProcessHandler(adapterProcess);
        adapterProcessHandler.startNotify();

        try {
          Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        // Create and start DAP client
        dapClient = new DAPClient(adapterProcess.getInputStream(), adapterProcess.getOutputStream(), this::handleEvent);
        dapClient.start();

        // Send initialize request
        DAPRequest initReq = new DAPRequest("initialize");
        initReq.setArgument("adapterID", "hashlink");
        initReq.setArgument("clientID", "intellij-haxe");
        initReq.setArgument("linesStartAt1", true);
        initReq.setArgument("columnsStartAt1", true);
        initReq.setArgument("pathFormat", "path");

        DAPResponse initResp;
        try {
          initResp = dapClient.sendRequest(initReq).get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          printToConsole("Failed to initialize DAP adapter\n");
          ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
          return;
        }

        if (!initResp.isSuccess()) {
          printToConsole("Failed to initialize DAP adapter\n");
          ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
          return;
        }

        // Record capabilities
        capSupportsConditionalBreakpoints = initResp.getBodyBool("supportsConditionalBreakpoints", false);
        capSupportsLogPoints = initResp.getBodyBool("supportsLogPoints", false);
        capSupportsEvaluateForHovers = initResp.getBodyBool("supportsEvaluateForHovers", false);
        capSupportsSingleThreadExecution = initResp.getBodyBool("supportsSingleThreadExecutionRequests", false);

        // Send attach or launch request
        String effectiveCwd = config.getWorkingDirectory();
        if (effectiveCwd == null || effectiveCwd.isBlank()) {
          effectiveCwd = config.getProject().getBasePath();
        }

        DAPResponse connectResp;
        if (useLaunchMode) {
          String hlPath = config.getHlExecutablePath();
          String progPath = config.getProgramPath();
          int port = config.getDebugPort();
          List<String> srcPaths = config.getSourcePaths();

          // On macOS arm64, ensure HL has the get-task-allow entitlement
          // so the debugger can attach via task_for_pid
          ensureDebugEntitlement(hlPath);

          // Kill any stale HL process that may be holding the debug port
          killStaleProcessOnPort(port);

          LOG.info("[HL Debug] Launch params: hl=" + hlPath + " program=" + progPath +
                   " port=" + port + " cwd=" + effectiveCwd + " classPaths=" + srcPaths);
          LOG.info("[HL Debug] Adapter alive: " + adapterProcess.isAlive());
          printToConsole("Launching HashLink via debug adapter (cwd: " + effectiveCwd + ")...\n");

          DAPRequest launchReq = new DAPRequest("launch");
          launchReq.setArgument("cwd", effectiveCwd);
          launchReq.setArgument("hl", hlPath);
          launchReq.setArgument("program", progPath);
          launchReq.setArgument("port", port);
          launchReq.setArgument("classPaths", srcPaths);

          // Pass environment so HL can find its shared libraries
          Map<String, String> env = new LinkedHashMap<>();
          String dylibPath = getShellEnv("DYLD_LIBRARY_PATH");
          if (dylibPath != null && !dylibPath.isEmpty()) {
            env.put("DYLD_LIBRARY_PATH", dylibPath);
          } else {
            java.io.File hlDir = new java.io.File(config.getHlExecutablePath()).getParentFile();
            if (hlDir != null && hlDir.isDirectory()) {
              env.put("DYLD_LIBRARY_PATH", hlDir.getAbsolutePath());
            }
          }
          String ldPath = getShellEnv("LD_LIBRARY_PATH");
          if (ldPath != null && !ldPath.isEmpty()) {
            env.put("LD_LIBRARY_PATH", ldPath);
          }
          // User-configured environment variables from the run configuration. The
          // adapter applies the launch "env" map to the spawned HL process, so these
          // must go here (setting them on the adapter's own process does not reach HL).
          Map<String, String> userEnv = config.getEnvironmentVariables();
          if (userEnv != null && !userEnv.isEmpty()) {
            env.putAll(userEnv);
          }
          if (!env.isEmpty()) {
            launchReq.setArgument("env", env);
          }

          String progArgs = config.getProgramArguments();
          if (progArgs != null && !progArgs.isBlank()) {
            launchReq.setArgument("args", List.of(progArgs.split("\\s+")));
          }

          connectResp = dapClient.sendRequest(launchReq).get(15, TimeUnit.SECONDS);
        } else {
          printToConsole("Connecting to HashLink on port " + config.getDebugPort() + " (cwd: " + effectiveCwd + ")...\n");

          DAPRequest attachReq = new DAPRequest("attach");
          attachReq.setArgument("port", config.getDebugPort());
          attachReq.setArgument("host", "127.0.0.1");
          attachReq.setArgument("cwd", effectiveCwd);
          attachReq.setArgument("classPaths", config.getSourcePaths());
          attachReq.setArgument("program", config.getProgramPath());

          connectResp = dapClient.sendRequest(attachReq).get(15, TimeUnit.SECONDS);
        }

        if (!connectResp.isSuccess()) {
          String msg = connectResp.getMessage();
          printToConsole("Failed to " + (useLaunchMode ? "launch" : "attach to") + " HashLink" +
                         (msg != null ? ": " + msg : "") + "\n");
          ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
          return;
        }

        // Flush pending breakpoints before configurationDone
        LOG.info("[HL Debug] Flushing pending breakpoints: " + breakpointsByFile.keySet().size() + " files");
        for (String filePath : breakpointsByFile.keySet()) {
          sendBreakpointsForFile(filePath);
        }

        // Send configurationDone request
        LOG.info("[HL Debug] Sending configurationDone");
        DAPRequest cfgDoneReq = new DAPRequest("configurationDone");
        DAPResponse cfgResp = dapClient.sendRequest(cfgDoneReq).get(5, TimeUnit.SECONDS);
        LOG.info("[HL Debug] configurationDone response: success=" + cfgResp.isSuccess());

        sessionConnected = true;

        // Resend any breakpoints registered during the configurationDone wait
        LOG.info("[HL Debug] Re-flushing breakpoints post-configDone: " + breakpointsByFile.keySet().size() + " files");
        for (String filePath : breakpointsByFile.keySet()) {
          sendBreakpointsForFile(filePath);
        }

        printToConsole("HashLink debugger connected. Running.\n");
      } catch (Exception e) {
        LOG.error("Error during DAP session startup", e);
        // Try to capture adapter exit code and stderr for diagnostics
        if (adapterProcess != null) {
          try {
            boolean exited = adapterProcess.waitFor(1, TimeUnit.SECONDS);
            if (exited) {
              LOG.error("[HL Debug] Adapter exited with code: " + adapterProcess.exitValue());
            } else {
              LOG.error("[HL Debug] Adapter still running despite error");
            }
          } catch (InterruptedException ie) {
            // ignore
          }
        }
        // Read stderr log file
        try {
          java.io.File stderrLog = new java.io.File(System.getProperty("java.io.tmpdir"), "hl_adapter_stderr.log");
          if (stderrLog.exists()) {
            String stderr = new String(java.nio.file.Files.readAllBytes(stderrLog.toPath()), StandardCharsets.UTF_8);
            if (!stderr.isBlank()) {
              LOG.error("[HL Debug] Adapter stderr:\n" + stderr.substring(0, Math.min(stderr.length(), 2000)));
            }
          }
        } catch (Exception ex) {
          LOG.debug("Could not read adapter stderr log", ex);
        }
        ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
      }
    });
  }

  private void handleEvent(DAPEvent event) {
    LOG.info("[HL Debug] Event received: " + event.getEvent());
    switch (event.getEvent()) {
      case "stopped":
        int threadId = event.getBodyInt("threadId", -1);
        String reason = event.getBodyString("reason", "unknown");
        LOG.info("[HL Debug] STOPPED event: threadId=" + threadId + " reason=" + reason);
        currentThreadId = threadId;
        // Clean up temporary run-to-cursor breakpoint
        String cleanupFile = runToCursorCleanupFile;
        if (cleanupFile != null) {
          runToCursorCleanupFile = null;
          ApplicationManager.getApplication().executeOnPooledThread(() -> sendBreakpointsForFile(cleanupFile));
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
          HLSuspendContext context = new HLSuspendContext(this, threadId);
          ApplicationManager.getApplication().invokeLater(() -> {
            getSession().positionReached(context);
          });
        });
        break;
      case "continued":
        break;  // no-op
      case "terminated":
      case "exited":
        ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
        break;
      case "output":
        printToConsole(event.getBodyString("output", ""));
        break;
      case "thread":
        LOG.info("Thread event: " + event.getBodyString("reason", "") +
            " threadId=" + event.getBodyInt("threadId", -1));
        break;
      case "breakpoint":
        LOG.info("Breakpoint event received");
        break;
    }
  }

  // --- Stepping methods ---

  @Override
  public void startPausing() {
    if (dapClient == null || !dapClient.isRunning()) return;
    try {
      DAPRequest r = new DAPRequest("pause");
      r.setArgument("threadId", currentThreadId);
      dapClient.sendRequest(r).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error("Failed to send pause request", e);
      ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
    }
  }

  @Override
  public void resume(@Nullable XSuspendContext context) {
    LOG.info("[HL Debug] resume() called, context=" + (context != null ? context.getClass().getSimpleName() : "null"));
    if (dapClient == null || !dapClient.isRunning()) return;
    try {
      boolean singleThread = false;
      if (context != null) {
        XExecutionStack activeStack = context.getActiveExecutionStack();
        if (activeStack != null) {
          XStackFrame topFrame = activeStack.getTopFrame();
          if (topFrame != null) {
            XSourcePosition position = topFrame.getSourcePosition();
            if (position != null) {
              String filePath = position.getFile().getPath();
              int line0 = position.getLine();
              List<XLineBreakpoint<?>> bps = breakpointsByFile.get(filePath);
              if (bps != null) {
                for (XLineBreakpoint<?> bp : bps) {
                  XSourcePosition bpPos = bp.getSourcePosition();
                  if (bpPos != null && bpPos.getLine() == line0) {
                    singleThread = bp.getSuspendPolicy() == SuspendPolicy.THREAD
                                   && capSupportsSingleThreadExecution;
                    break;
                  }
                }
              }
            }
          }
        }
      }
      DAPRequest r = new DAPRequest("continue");
      r.setArgument("threadId", currentThreadId);
      r.setArgument("singleThread", singleThread);
      dapClient.sendRequest(r).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error("Failed to send continue request", e);
      ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
    }
  }

  @Override
  public void startStepOver(@Nullable XSuspendContext context) {
    if (dapClient == null || !dapClient.isRunning()) return;
    try {
      DAPRequest r = new DAPRequest("next");
      r.setArgument("threadId", currentThreadId);
      dapClient.sendRequest(r).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error("Failed to send stepOver request", e);
      ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
    }
  }

  @Override
  public void startStepInto(@Nullable XSuspendContext context) {
    if (dapClient == null || !dapClient.isRunning()) return;
    try {
      DAPRequest r = new DAPRequest("stepIn");
      r.setArgument("threadId", currentThreadId);
      dapClient.sendRequest(r).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error("Failed to send stepIn request", e);
      ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
    }
  }

  @Override
  public void startStepOut(@Nullable XSuspendContext context) {
    if (dapClient == null || !dapClient.isRunning()) return;
    try {
      DAPRequest r = new DAPRequest("stepOut");
      r.setArgument("threadId", currentThreadId);
      dapClient.sendRequest(r).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error("Failed to send stepOut request", e);
      ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
    }
  }

  @Override
  public void runToPosition(@NotNull XSourcePosition position) {
    LOG.info("[HL Debug] runToPosition(1-arg) called: " + position.getFile().getPath() + ":" + (position.getLine() + 1));
    doRunToPosition(position);
  }

  @Override
  public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
    LOG.info("[HL Debug] runToPosition(2-arg) called: " + position.getFile().getPath() + ":" + (position.getLine() + 1) +
             " context=" + (context != null ? context.getClass().getSimpleName() : "null"));
    doRunToPosition(position);
  }

  private void doRunToPosition(@NotNull XSourcePosition position) {
    if (dapClient == null || !dapClient.isRunning()) {
      LOG.warn("[HL Debug] runToPosition: dapClient not available");
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        // Set a temporary breakpoint at the target position, then continue.
        // We install ONLY the target breakpoint (removing existing ones in this file)
        // to prevent the DAP adapter from re-hitting the current-line breakpoint.
        // When the debuggee stops, the original breakpoints are restored.
        String filePath = position.getFile().getPath();
        int targetLine = position.getLine() + 1; // DAP is 1-based

        LOG.info("[HL Debug] doRunToPosition: setting temp breakpoint at " + filePath + ":" + targetLine);

        DAPRequest req = new DAPRequest("setBreakpoints");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("path", filePath);
        req.setArgument("source", source);

        // Only include the target line — exclude existing breakpoints in this file
        // to avoid the DAP adapter re-installing a BRK at the current position,
        // which would cause the continue to immediately re-hit it.
        List<Map<String, Object>> breakpointEntries = new ArrayList<>();
        Map<String, Object> targetEntry = new LinkedHashMap<>();
        targetEntry.put("line", targetLine);
        breakpointEntries.add(targetEntry);
        req.setArgument("breakpoints", breakpointEntries);

        DAPResponse bpResp = dapClient.sendRequest(req).get(5, TimeUnit.SECONDS);
        LOG.info("[HL Debug] doRunToPosition: setBreakpoints response: success=" + bpResp.isSuccess());

        // Continue execution
        DAPRequest continueReq = new DAPRequest("continue");
        continueReq.setArgument("threadId", currentThreadId);
        DAPResponse contResp = dapClient.sendRequest(continueReq).get(5, TimeUnit.SECONDS);
        LOG.info("[HL Debug] doRunToPosition: continue response: success=" + contResp.isSuccess());

        // When the stopped event fires, the cleanup restores original breakpoints.
        runToCursorCleanupFile = filePath;
      } catch (Exception e) {
        LOG.error("Failed runToPosition", e);
      }
    });
  }

  // Track file needing run-to-cursor cleanup (temporary breakpoint removal)
  private volatile String runToCursorCleanupFile = null;

  // --- Stop ---

  @Override
  public void stop() {
    sessionConnected = false;
    if (dapClient != null && dapClient.isRunning()) {
      try {
        DAPRequest disconnectReq = new DAPRequest("disconnect");
        disconnectReq.setArgument("terminateDebuggee", true);
        dapClient.sendRequest(disconnectReq).get(3, TimeUnit.SECONDS);
      } catch (Exception e) {
        LOG.debug("Disconnect request failed (expected if adapter already exited)", e);
      }
    }
    if (dapClient != null) {
      dapClient.stop();
    }
    // Kill all child processes (HL) before killing the adapter itself
    if (adapterProcess != null) {
      try {
        adapterProcess.descendants().forEach(ph -> {
          LOG.info("[HL Debug] Killing child process: pid=" + ph.pid());
          ph.destroyForcibly();
        });
      } catch (Exception e) {
        LOG.debug("Error killing adapter children", e);
      }
      adapterProcess.destroyForcibly();
    }
    if (adapterProcessHandler != null) {
      adapterProcessHandler.destroyProcess();
    }
    if (executionResult != null && executionResult.getProcessHandler() != null) {
      executionResult.getProcessHandler().destroyProcess();
    }
    // Final safety: kill anything still holding the debug port
    try {
      killStaleProcessOnPort(config.getDebugPort());
    } catch (Exception e) {
      LOG.debug("Port cleanup on stop failed", e);
    }
  }

  // --- Overrides ---

  @NotNull
  @Override
  public XBreakpointHandler<?>[] getBreakpointHandlers() {
    return new XBreakpointHandler<?>[]{
        new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<?>>>(HLBreakpointType.class) {
          @Override
          public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint) {
            HLDebugProcess.this.registerBreakpoint(breakpoint);
          }

          @Override
          public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint, boolean temporary) {
            HLDebugProcess.this.unregisterBreakpoint(breakpoint);
          }
        },
        new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>>(HaxeBreakpointType.class) {
          @Override
          public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
            HLDebugProcess.this.registerBreakpoint(breakpoint);
          }

          @Override
          public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint, boolean temporary) {
            HLDebugProcess.this.unregisterBreakpoint(breakpoint);
          }
        }
    };
  }

  @Nullable
  @Override
  protected ProcessHandler doGetProcessHandler() {
    if (executionResult != null && executionResult.getProcessHandler() != null) {
      return executionResult.getProcessHandler();
    }
    return adapterProcessHandler;
  }

  @NotNull
  @Override
  public ExecutionConsole createConsole() {
    if (executionResult != null && executionResult.getExecutionConsole() != null) {
      ExecutionConsole ec = executionResult.getExecutionConsole();
      if (ec instanceof ConsoleView cv) {
        this.consoleView = cv;
      }
      return ec;
    }
    // Create our own console for launch mode (executionResult is null)
    ConsoleView cv = TextConsoleBuilderFactory.getInstance()
        .createBuilder(getSession().getProject()).getConsole();
    this.consoleView = cv;
    return cv;
  }

  @NotNull
  @Override
  public XDebuggerEditorsProvider getEditorsProvider() {
    return new HLDebuggerEditorsProvider();
  }

  // --- HLDebugProcessInterface implementations ---

  @Override
  public DAPClient getDapClient() {
    return dapClient;
  }

  @Override
  public int getCurrentThreadId() {
    return currentThreadId;
  }

  @Override
  public List<Map<String, Object>> getScopes(int frameId) {
    try {
      DAPRequest r = new DAPRequest("scopes");
      r.setArgument("frameId", frameId);
      return dapClient.sendRequest(r).get(5, TimeUnit.SECONDS).getBodyList("scopes");
    } catch (Exception e) {
      LOG.error("Failed to get scopes for frameId=" + frameId, e);
      return Collections.emptyList();
    }
  }

  @Override
  public List<Map<String, Object>> getVariables(int variablesReference) {
    try {
      DAPRequest r = new DAPRequest("variables");
      r.setArgument("variablesReference", variablesReference);
      return dapClient.sendRequest(r).get(5, TimeUnit.SECONDS).getBodyList("variables");
    } catch (Exception e) {
      LOG.error("Failed to get variables for reference=" + variablesReference, e);
      return Collections.emptyList();
    }
  }

  @Override
  public Map<String, Object> evaluate(String expression, int frameId, String context) {
    try {
      DAPRequest r = new DAPRequest("evaluate");
      r.setArgument("expression", expression);
      r.setArgument("frameId", frameId);
      r.setArgument("context", context);
      DAPResponse resp = dapClient.sendRequest(r).get(5, TimeUnit.SECONDS);
      if (!resp.isSuccess()) {
        LOG.debug("evaluate failed for expression: " + expression
                  + "; message=" + resp.getMessage());
        return null;
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("result", resp.getBodyString("result", ""));
      result.put("variablesReference", resp.getBodyInt("variablesReference", 0));
      result.put("type", resp.getBodyString("type", ""));
      return result;
    } catch (Exception e) {
      LOG.error("Failed to evaluate expression: " + expression, e);
      return null;
    }
  }

  // --- Breakpoint methods ---

  private void registerBreakpoint(XLineBreakpoint<?> bp) {
    XSourcePosition pos = bp.getSourcePosition();
    if (pos == null) return;
    String filePath = pos.getFile().getPath();
    LOG.info("[HL Debug] registerBreakpoint: " + filePath + ":" + (pos.getLine() + 1) +
             " sessionConnected=" + sessionConnected +
             " dapAlive=" + (dapClient != null && dapClient.isRunning()));
    breakpointsByFile.computeIfAbsent(filePath, k -> new CopyOnWriteArrayList<>()).add(bp);
    if (sessionConnected && dapClient != null && dapClient.isRunning()) {
      ApplicationManager.getApplication().executeOnPooledThread(() -> sendBreakpointsForFile(filePath));
    }
  }

  private void unregisterBreakpoint(XLineBreakpoint<?> bp) {
    XSourcePosition pos = bp.getSourcePosition();
    if (pos == null) return;
    String filePath = pos.getFile().getPath();
    List<XLineBreakpoint<?>> bps = breakpointsByFile.get(filePath);
    if (bps != null) {
      bps.remove(bp);
    }
    if (sessionConnected && dapClient != null && dapClient.isRunning()) {
      ApplicationManager.getApplication().executeOnPooledThread(() -> sendBreakpointsForFile(filePath));
    }
  }

  private void sendBreakpointsForFile(String filePath) {
    try {
      List<XLineBreakpoint<?>> bps = breakpointsByFile.getOrDefault(filePath, Collections.emptyList());
      LOG.info("[HL Debug] sendBreakpointsForFile: " + filePath + " count=" + bps.size());

      DAPRequest req = new DAPRequest("setBreakpoints");
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("path", filePath);
      req.setArgument("source", source);

      List<Map<String, Object>> breakpointEntries = new ArrayList<>();
      List<XLineBreakpoint<?>> requestedBps = new ArrayList<>();
      for (XLineBreakpoint<?> bp : bps) {
        XSourcePosition pos = bp.getSourcePosition();
        if (pos == null) continue;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("line", pos.getLine() + 1);
        if (capSupportsConditionalBreakpoints && bp.getConditionExpression() != null
            && !bp.getConditionExpression().getExpression().isBlank()) {
          entry.put("condition", bp.getConditionExpression().getExpression());
        }
        if (capSupportsLogPoints && bp.getLogExpressionObject() != null
            && !bp.getLogExpressionObject().getExpression().isBlank()) {
          entry.put("logMessage", bp.getLogExpressionObject().getExpression());
        }
        breakpointEntries.add(entry);
        requestedBps.add(bp);
      }
      req.setArgument("breakpoints", breakpointEntries);

      LOG.info("[HL Debug] Sending setBreakpoints for " + filePath + " with " + breakpointEntries.size() + " breakpoints: " + breakpointEntries);
      DAPResponse resp = dapClient.sendRequest(req).get(5, TimeUnit.SECONDS);
      LOG.info("[HL Debug] setBreakpoints response: success=" + resp.isSuccess() + " message=" + resp.getMessage());
      if (resp.isSuccess()) {
        List<Map<String, Object>> results = resp.getBodyList("breakpoints");
        LOG.info("[HL Debug] setBreakpoints results: " + results);
        for (int i = 0; i < results.size() && i < requestedBps.size(); i++) {
          Map<String, Object> result = results.get(i);
          if (Boolean.TRUE.equals(result.get("verified"))) {
            LOG.info("[HL Debug] Breakpoint verified: line=" + result.get("line"));
            getSession().updateBreakpointPresentation(requestedBps.get(i), null, null);
          } else {
            String message = result.get("message") instanceof String s ? s : "Unverified";
            LOG.info("[HL Debug] Breakpoint NOT verified: " + message);
            getSession().updateBreakpointPresentation(requestedBps.get(i), AllIcons.Debugger.Db_invalid_breakpoint, message);
          }
        }
      } else {
        LOG.warn("[HL Debug] setBreakpoints FAILED: " + resp.getMessage());
      }
    } catch (Exception e) {
      LOG.error("Failed to send breakpoints for file: " + filePath, e);
    }
  }

  // --- Helper ---

  private void printToConsole(String message) {
    printToConsole(message, ConsoleViewContentType.NORMAL_OUTPUT);
  }

  private void printToConsole(String message, ConsoleViewContentType type) {
    ConsoleView cv = this.consoleView;
    if (cv != null) {
      cv.print(message, type);
    } else {
      // Console not yet created — log as fallback
      LOG.info("[HL Debug] " + message.trim());
    }
  }

  private static String getShellEnv(String name) {
    try {
      String value = EnvironmentUtil.getValue(name);
      if (value != null && !value.isEmpty()) return value;
    } catch (Exception e) {
      // Fall through
    }
    return System.getenv(name);
  }

  /**
   * On macOS, ensures the HL executable has the {@code com.apple.security.get-task-allow}
   * entitlement. Without this, {@code task_for_pid()} fails when the debugger node process
   * is launched from a GUI application (like the IDE) rather than a terminal.
   * <p>
   * This re-signs the binary ad-hoc with the entitlement. It's idempotent —
   * if the entitlement is already present, the signing is skipped.
   */
  /**
   * A lightweight ProcessHandler wrapping the debug adapter (node) process.
   * This enables the IDE's stop button and process lifecycle tracking.
   */
  private static class AdapterProcessHandler extends ProcessHandler {
    private final Process process;

    AdapterProcessHandler(Process process) {
      this.process = process;
      // Monitor the process in a daemon thread so we get notified when it exits
      Thread monitor = new Thread(() -> {
        try {
          int exitCode = process.waitFor();
          notifyProcessTerminated(exitCode);
        } catch (InterruptedException e) {
          // ignore
        }
      }, "HL-Adapter-Monitor");
      monitor.setDaemon(true);
      monitor.start();
    }

    @Override
    protected void destroyProcessImpl() {
      process.destroyForcibly();
    }

    @Override
    protected void detachProcessImpl() {
      process.destroyForcibly();
      notifyProcessDetached();
    }

    @Override
    public boolean detachIsDefault() {
      return false;
    }

    @Nullable
    @Override
    public OutputStream getProcessInput() {
      return process.getOutputStream();
    }
  }

  /**
   * Checks if the given port is occupied and kills the process holding it.
   * This prevents "hl exit code 4" errors when a previous debug session
   * left an orphan HL process.
   */
  private static void killStaleProcessOnPort(int port) {
    if (port <= 0) return;
    try {
      ProcessBuilder pb = new ProcessBuilder("lsof", "-ti", ":" + port);
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      proc.waitFor(3, TimeUnit.SECONDS);

      if (!output.isEmpty()) {
        for (String pidStr : output.split("\\n")) {
          pidStr = pidStr.trim();
          if (!pidStr.isEmpty()) {
            try {
              long pid = Long.parseLong(pidStr);
              LOG.info("[HL Debug] Killing stale process on port " + port + ": pid=" + pid);
              ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            } catch (NumberFormatException e) {
              // ignore
            }
          }
        }
        // Give the OS a moment to release the port
        Thread.sleep(500);
      }
    } catch (Exception e) {
      LOG.debug("Port cleanup failed for port " + port, e);
    }
  }

  private static void ensureDebugEntitlement(String hlPath) {
    if (!"Mac OS X".equals(System.getProperty("os.name"))) return;
    if (hlPath == null || hlPath.isBlank()) return;

    java.io.File hlFile = new java.io.File(hlPath);
    if (!hlFile.isFile()) return;

    try {
      // Check if the entitlement is already present
      ProcessBuilder checkPb = new ProcessBuilder("codesign", "-d", "--entitlements", "-", hlPath);
      checkPb.redirectErrorStream(true);
      Process checkProc = checkPb.start();
      String output = new String(checkProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      checkProc.waitFor(5, TimeUnit.SECONDS);

      if (output.contains("get-task-allow")) {
        LOG.debug("[HL Debug] HL binary already has get-task-allow entitlement");
        return;
      }

      LOG.info("[HL Debug] Signing HL binary with get-task-allow entitlement: " + hlPath);

      // Create a temporary entitlements plist
      java.io.File entFile = java.io.File.createTempFile("hl_debug_ent_", ".plist");
      entFile.deleteOnExit();
      java.nio.file.Files.writeString(entFile.toPath(),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
        "<plist version=\"1.0\"><dict>\n" +
        "<key>com.apple.security.get-task-allow</key><true/>\n" +
        "</dict></plist>\n");

      ProcessBuilder signPb = new ProcessBuilder(
        "codesign", "--force", "--sign", "-", "--entitlements", entFile.getAbsolutePath(), hlPath);
      signPb.redirectErrorStream(true);
      Process signProc = signPb.start();
      String signOutput = new String(signProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exitCode = signProc.waitFor();

      if (exitCode == 0) {
        LOG.info("[HL Debug] Successfully signed HL binary with debug entitlement");
      } else {
        LOG.warn("[HL Debug] Failed to sign HL binary (exit " + exitCode + "): " + signOutput);
      }

      entFile.delete();
    } catch (Exception e) {
      LOG.warn("[HL Debug] Could not check/sign HL binary entitlement", e);
    }
  }
}
