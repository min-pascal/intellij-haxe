package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPRequest;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HLSuspendContext extends XSuspendContext {

  private static final Logger LOG = Logger.getInstance(HLSuspendContext.class);

  private HLExecutionStack activeStack;
  private HLExecutionStack[] allStacks;

  public HLSuspendContext(HLDebugProcessInterface process, int stoppedThreadId) {
    try {
      DAPRequest threadsReq = new DAPRequest("threads");
      List<Map<String, Object>> threads = process.getDapClient()
          .sendRequest(threadsReq).get(5, TimeUnit.SECONDS).getBodyList("threads");

      List<HLExecutionStack> lazyStacks = new ArrayList<>();

      for (Map<String, Object> thread : threads) {
        int id = ((Number) thread.get("id")).intValue();
        String name = (String) thread.getOrDefault("name", "Thread " + id);

        if (id == stoppedThreadId) {
          DAPRequest stackReq = new DAPRequest("stackTrace");
          stackReq.setArgument("threadId", stoppedThreadId);
          List<Map<String, Object>> frames = process.getDapClient()
              .sendRequest(stackReq).get(5, TimeUnit.SECONDS).getBodyList("stackFrames");
          activeStack = new HLExecutionStack(process, id, name, frames);
        } else {
          lazyStacks.add(new HLExecutionStack(process, id, name));
        }
      }

      List<HLExecutionStack> all = new ArrayList<>();
      if (activeStack != null) {
        all.add(activeStack);
      }
      all.addAll(lazyStacks);
      allStacks = all.toArray(new HLExecutionStack[0]);
    } catch (Exception e) {
      LOG.error("Failed to build suspend context", e);
      activeStack = null;
      allStacks = new HLExecutionStack[0];
    }
  }

  @Nullable
  @Override
  public XExecutionStack getActiveExecutionStack() {
    return activeStack;
  }

  @Override
  public void computeExecutionStacks(@NotNull XExecutionStackContainer container) {
    container.addExecutionStack(Arrays.asList(allStacks), true);
  }
}
