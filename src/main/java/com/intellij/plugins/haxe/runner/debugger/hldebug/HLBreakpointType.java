package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HLBreakpointType extends XLineBreakpointType<XBreakpointProperties<?>> {

  protected HLBreakpointType() {
    super("HaxeHL", "HashLink Breakpoint");
  }

  @Override
  public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
    return file.getFileType() == HaxeFileType.INSTANCE;
  }

  @Nullable
  @Override
  public XBreakpointProperties<?> createBreakpointProperties(@NotNull VirtualFile file, int line) {
    return null;
  }

  @Override
  public int getPriority() {
    return 100;
  }

  @Nullable
  @Override
  public XDebuggerEditorsProvider getEditorsProvider(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint,
                                                     @NotNull Project project) {
    return new HLDebuggerEditorsProvider();
  }
}
