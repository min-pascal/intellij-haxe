package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.HaxeBreakpointType;
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

  private final HLRunConfiguration config;
  private final ExecutionResult executionResult;
  private DAPClient dapClient;
  private Process adapterProcess;
  private volatile int currentThreadId = -1;
  private boolean capSupportsConditionalBreakpoints = false;
  private boolean capSupportsLogPoints = false;
  private boolean capSupportsEvaluateForHovers = false;
  private boolean capSupportsSingleThreadExecution = false;
  private final Map<String, List<XLineBreakpoint<?>>> breakpointsByFile = new ConcurrentHashMap<>();
  private volatile boolean sessionConnected = false;

  public HLDebugProcess(@NotNull XDebugSession session, HLRunConfiguration config, ExecutionResult executionResult) {
    super(session);
    this.config = config;
    this.executionResult = executionResult;
  }

  @Override
  public void sessionInitialized() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        printToConsole("Starting HashLink debug adapter...\n");

        // Spawn adapter
        ProcessBuilder pb = new ProcessBuilder(config.getNodePath(), config.getAdapterPath());
        adapterProcess = pb.start();

        // Drain stderr on a daemon thread to prevent blocking and keep DAP framing clean
        Thread stderrReader = new Thread(() -> {
          try (BufferedReader reader = new BufferedReader(
              new InputStreamReader(adapterProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
              printToConsole(line + "\n", ConsoleViewContentType.ERROR_OUTPUT);
            }
          } catch (IOException e) {
            LOG.debug("Adapter stderr reader terminated", e);
          }
        }, "DAP-Stderr-Reader");
        stderrReader.setDaemon(true);
        stderrReader.start();

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

        // Send attach request
        String effectiveCwd = config.getWorkingDirectory();
        if (effectiveCwd == null || effectiveCwd.isBlank()) {
          effectiveCwd = config.getProject().getBasePath();
        }
        printToConsole("Connecting to HashLink on port " + config.getDebugPort() + " (cwd: " + effectiveCwd + ")...\n");

        DAPRequest attachReq = new DAPRequest("attach");
        attachReq.setArgument("port", config.getDebugPort());
        attachReq.setArgument("host", "127.0.0.1");
        attachReq.setArgument("cwd", effectiveCwd);
        attachReq.setArgument("classPaths", config.getSourcePaths());
        attachReq.setArgument("program", config.getProgramPath());

        DAPResponse attachResp = dapClient.sendRequest(attachReq).get(15, TimeUnit.SECONDS);
        if (!attachResp.isSuccess()) {
          printToConsole("Failed to attach to HashLink\n");
          ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
          return;
        }

        // Flush pending breakpoints before configurationDone
        for (String filePath : breakpointsByFile.keySet()) {
          sendBreakpointsForFile(filePath);
        }

        // Send configurationDone request
        DAPRequest cfgDoneReq = new DAPRequest("configurationDone");
        dapClient.sendRequest(cfgDoneReq).get(5, TimeUnit.SECONDS);

        sessionConnected = true;

        // Resend any breakpoints registered during the configurationDone wait
        for (String filePath : breakpointsByFile.keySet()) {
          sendBreakpointsForFile(filePath);
        }

        printToConsole("HashLink debugger connected. Running.\n");
      } catch (Exception e) {
        LOG.error("Error during DAP session startup", e);
        ApplicationManager.getApplication().invokeLater(() -> getSession().stop());
      }
    });
  }

  private void handleEvent(DAPEvent event) {
    switch (event.getEvent()) {
      case "stopped":
        int threadId = event.getBodyInt("threadId", -1);
        currentThreadId = threadId;
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

  // --- Stop ---

  @Override
  public void stop() {
    sessionConnected = false;
    if (dapClient != null && dapClient.isRunning()) {
      DAPRequest disconnectReq = new DAPRequest("disconnect");
      disconnectReq.setArgument("terminateDebuggee", true);
      dapClient.sendRequest(disconnectReq);
    }
    if (dapClient != null) {
      dapClient.stop();
    }
    if (adapterProcess != null) {
      adapterProcess.destroyForcibly();
    }
    if (executionResult.getProcessHandler() != null) {
      executionResult.getProcessHandler().destroyProcess();
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
    return executionResult.getProcessHandler();
  }

  @NotNull
  @Override
  public ExecutionConsole createConsole() {
    return executionResult.getExecutionConsole();
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

      DAPResponse resp = dapClient.sendRequest(req).get(5, TimeUnit.SECONDS);
      if (resp.isSuccess()) {
        List<Map<String, Object>> results = resp.getBodyList("breakpoints");
        for (int i = 0; i < results.size() && i < requestedBps.size(); i++) {
          Map<String, Object> result = results.get(i);
          if (Boolean.TRUE.equals(result.get("verified"))) {
            getSession().updateBreakpointPresentation(requestedBps.get(i), null, null);
          } else {
            String message = result.get("message") instanceof String s ? s : "Unverified";
            getSession().updateBreakpointPresentation(requestedBps.get(i), AllIcons.Debugger.Db_invalid_breakpoint, message);
          }
        }
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
    ExecutionConsole console = executionResult.getExecutionConsole();
    if (console instanceof ConsoleView consoleView) {
      consoleView.print(message, type);
    }
  }
}
