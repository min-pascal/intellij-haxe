package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPRequest;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HLExecutionStack extends XExecutionStack {

  private static final Logger LOG = Logger.getInstance(HLExecutionStack.class);

  private final int threadId;
  private final HLDebugProcessInterface process;
  private final List<HLStackFrame> preloadedFrames;

  /**
   * Constructor for active thread with pre-loaded frames.
   */
  public HLExecutionStack(HLDebugProcessInterface process, int threadId, String threadName,
                           List<Map<String, Object>> frameData) {
    super(threadName, AllIcons.Debugger.ThreadSuspended);
    this.process = process;
    this.threadId = threadId;
    this.preloadedFrames = new ArrayList<>();
    for (Map<String, Object> frameMap : frameData) {
      preloadedFrames.add(new HLStackFrame(process, frameMap));
    }
  }

  /**
   * Constructor for lazy threads (frames loaded on demand).
   */
  public HLExecutionStack(HLDebugProcessInterface process, int threadId, String threadName) {
    super(threadName, AllIcons.Debugger.ThreadRunning);
    this.process = process;
    this.threadId = threadId;
    this.preloadedFrames = null;
  }

  @Nullable
  @Override
  public XStackFrame getTopFrame() {
    return preloadedFrames != null && !preloadedFrames.isEmpty() ? preloadedFrames.get(0) : null;
  }

  @Override
  public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
    if (preloadedFrames != null) {
      int startIndex = Math.min(firstFrameIndex, preloadedFrames.size());
      container.addStackFrames(preloadedFrames.subList(startIndex, preloadedFrames.size()), true);
    } else {
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        try {
          DAPRequest stackReq = new DAPRequest("stackTrace");
          stackReq.setArgument("threadId", threadId);
          List<Map<String, Object>> frameData = process.getDapClient()
              .sendRequest(stackReq).get(5, TimeUnit.SECONDS).getBodyList("stackFrames");
          List<HLStackFrame> frames = new ArrayList<>();
          for (Map<String, Object> frameMap : frameData) {
            frames.add(new HLStackFrame(process, frameMap));
          }
          int startIndex = Math.min(firstFrameIndex, frames.size());
          container.addStackFrames(frames.subList(startIndex, frames.size()), true);
        } catch (Exception e) {
          LOG.error("Failed to load stack frames for thread " + threadId, e);
          container.addStackFrames(Collections.emptyList(), true);
        }
      });
    }
  }
}
