package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueModifier;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class HLValue extends XNamedValue {

  private static final Logger LOG = Logger.getInstance(HLValue.class);

  private final HLDebugProcessInterface process;
  private final String type;
  private final String value;
  private final int variablesReference;

  public HLValue(HLDebugProcessInterface process, Map<String, Object> varData) {
    super((String) varData.getOrDefault("name", "?"));
    this.process = process;
    this.type = (String) varData.getOrDefault("type", "");
    this.value = (String) varData.getOrDefault("value", "");
    this.variablesReference = ((Number) varData.getOrDefault("variablesReference", 0)).intValue();
  }

  @Override
  public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
    node.setPresentation(AllIcons.Debugger.Value, type, value, variablesReference > 0);
  }

  @Override
  public void computeChildren(@NotNull XCompositeNode node) {
    if (variablesReference <= 0) {
      node.addChildren(XValueChildrenList.EMPTY, true);
      return;
    }

    try {
      List<Map<String, Object>> variables = process.getVariables(variablesReference);
      XValueChildrenList childrenList = new XValueChildrenList();
      for (Map<String, Object> varMap : variables) {
        String name = (String) varMap.getOrDefault("name", "?");
        childrenList.add(name, new HLValue(process, varMap));
      }
      node.addChildren(childrenList, true);
    } catch (Exception e) {
      LOG.error("Failed to compute children for variablesReference=" + variablesReference, e);
      node.addChildren(XValueChildrenList.EMPTY, true);
    }
  }

  @Nullable
  @Override
  public XValueModifier getModifier() {
    return null;
  }
}
