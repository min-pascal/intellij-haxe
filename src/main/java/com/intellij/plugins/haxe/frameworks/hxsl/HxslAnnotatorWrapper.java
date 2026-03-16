package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public class HxslAnnotatorWrapper implements Annotator {

  private final Annotator delegate;

  public HxslAnnotatorWrapper(Annotator delegate) {
    this.delegate = delegate;
  }

  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (HxslUtil.isInsideHxslBlock(element)) return;
    delegate.annotate(element, holder);
  }
}
