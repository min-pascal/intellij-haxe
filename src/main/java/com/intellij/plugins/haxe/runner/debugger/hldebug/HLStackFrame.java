package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class HLStackFrame extends XStackFrame {

  private static final Logger LOG = Logger.getInstance(HLStackFrame.class);

  private final int frameId;
  private final String functionName;
  private final XSourcePosition sourcePosition;
  private final HLDebugProcessInterface process;

  @SuppressWarnings("unchecked")
  public HLStackFrame(HLDebugProcessInterface process, Map<String, Object> frameData) {
    this.process = process;
    this.frameId = ((Number) frameData.get("id")).intValue();
    this.functionName = (String) frameData.getOrDefault("name", "unknown");

    XSourcePosition pos = null;
    Map<String, Object> source = (Map<String, Object>) frameData.get("source");
    String path = source != null ? (String) source.get("path") : null;
    int line = frameData.get("line") != null ? ((Number) frameData.get("line")).intValue() : 0;

    if (path != null && !path.isEmpty() && line > 0) {
      VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
      if (vf != null) {
        pos = XDebuggerUtil.getInstance().createPosition(vf, line - 1);
      }
    }
    this.sourcePosition = pos;
  }

  @Nullable
  @Override
  public XSourcePosition getSourcePosition() {
    return sourcePosition;
  }

  @Nullable
  @Override
  public XDebuggerEvaluator getEvaluator() {
    return new HLDebuggerEvaluator(process, frameId);
  }

  @Nullable
  @Override
  public Object getEqualityObject() {
    return frameId;
  }

  @Override
  public void customizePresentation(@NotNull ColoredTextContainer component) {
    if (sourcePosition != null) {
      component.append(functionName + "  [" + sourcePosition.getFile().getName() + ":"
          + (sourcePosition.getLine() + 1) + "]", SimpleTextAttributes.REGULAR_ATTRIBUTES);
    } else {
      component.append(functionName, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }
    component.setIcon(AllIcons.Debugger.Frame);
  }

  @Override
  public void computeChildren(@NotNull XCompositeNode node) {
    try {
      List<Map<String, Object>> scopes = process.getScopes(frameId);
      XValueChildrenList childrenList = new XValueChildrenList();

      for (Map<String, Object> scope : scopes) {
        int variablesReference = ((Number) scope.get("variablesReference")).intValue();
        List<Map<String, Object>> variables = process.getVariables(variablesReference);
        for (Map<String, Object> varMap : variables) {
          String name = (String) varMap.getOrDefault("name", "?");
          childrenList.add(name, new HLValue(process, varMap));
        }
      }

      node.addChildren(childrenList, true);
    } catch (Exception e) {
      LOG.error("Failed to compute children for frame " + frameId, e);
      node.addChildren(XValueChildrenList.EMPTY, true);
    }
  }
}
