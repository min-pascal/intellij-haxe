package com.intellij.plugins.haxe.runner.debugger.hldebug;

import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.runner.debugger.HaxeDebuggerSupportUtils;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HLDebuggerEditorsProvider extends XDebuggerEditorsProvider {

  @NotNull
  @Override
  public FileType getFileType() {
    return HaxeFileType.INSTANCE;
  }

  @NotNull
  @Override
  public Document createDocument(@NotNull Project project,
                                 @NotNull XExpression expression,
                                 @Nullable XSourcePosition sourcePosition,
                                 @NotNull EvaluationMode mode) {
    try {
      Document doc = HaxeDebuggerSupportUtils.createDocument(
          expression.getExpression(),
          project,
          sourcePosition != null ? sourcePosition.getFile() : null,
          sourcePosition != null ? sourcePosition.getOffset() : -1
      );
      if (doc != null) {
        return doc;
      }
    } catch (Exception ignored) {
    }

    // Fallback: create a plain text document
    PsiFile psiFile = PsiFileFactory.getInstance(project)
        .createFileFromText("hl_eval.txt", PlainTextLanguage.INSTANCE, expression.getExpression());
    Document fallback = PsiDocumentManager.getInstance(project).getDocument(psiFile);
    assert fallback != null;
    return fallback;
  }
}
