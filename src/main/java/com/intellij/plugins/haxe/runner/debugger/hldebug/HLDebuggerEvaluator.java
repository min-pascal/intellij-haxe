package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class HLDebuggerEvaluator extends XDebuggerEvaluator {

  private final HLDebugProcessInterface process;
  private final int frameId;

  public HLDebuggerEvaluator(HLDebugProcessInterface process, int frameId) {
    this.process = process;
    this.frameId = frameId;
  }

  @Override
  public void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback,
                        @Nullable XSourcePosition expressionPosition) {
    evaluateExpression(expression, callback);
  }

  @Override
  public void evaluate(@NotNull XExpression expression, @NotNull XEvaluationCallback callback,
                        @Nullable XSourcePosition expressionPosition) {
    evaluateExpression(expression.getExpression(), callback);
  }

  private void evaluateExpression(@NotNull String expression, @NotNull XEvaluationCallback callback) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Object result = process.evaluate(expression, frameId, "watch");
        if (result != null) {
          Map<String, Object> syntheticMap = new HashMap<>();
          syntheticMap.put("name", expression);
          syntheticMap.put("value", result.toString());
          syntheticMap.put("type", "");
          syntheticMap.put("variablesReference", 0);
          callback.evaluated(new HLValue(process, syntheticMap));
        } else {
          callback.errorOccurred("Evaluation failed");
        }
      } catch (Exception e) {
        callback.errorOccurred("Evaluation failed");
      }
    });
  }
}
