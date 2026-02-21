package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HLDebugRunner extends GenericProgramRunner<RunnerSettings> {

  @NotNull
  @Override
  public String getRunnerId() {
    return "HLDebugRunner";
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof HLRunConfiguration;
  }

  @Nullable
  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment env)
      throws ExecutionException {
    HLRunConfiguration config = (HLRunConfiguration) env.getRunProfile();
    HLDebuggerState hlState = (HLDebuggerState) state;
    ExecutionResult executionResult = hlState.execute(env.getExecutor(), this);

    XDebugSession debugSession = XDebuggerManager.getInstance(env.getProject()).startSession(
        env,
        new XDebugProcessStarter() {
          @NotNull
          @Override
          public XDebugProcess start(@NotNull XDebugSession session) throws ExecutionException {
            return new HLDebugProcess(session, config, executionResult);
          }
        }
    );

    return debugSession.getRunContentDescriptor();
  }
}
