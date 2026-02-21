package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.plugins.haxe.runner.debugger.hldebug.dap.DAPClient;

import java.util.List;
import java.util.Map;

public interface HLDebugProcessInterface {

  DAPClient getDapClient();

  int getCurrentThreadId();

  List<Map<String, Object>> getScopes(int frameId);

  List<Map<String, Object>> getVariables(int variablesReference);

  Object evaluate(String expression, int frameId, String context);
}
