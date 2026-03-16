package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeFieldDeclaration;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HxslUtil {

  private HxslUtil() {
  }

  @Nullable
  public static HaxeFieldDeclaration getEnclosingHxslField(@NotNull PsiElement element) {
    // Walk up through ALL ancestor HaxeFieldDeclaration nodes — the nearest one may be
    // an inner field (e.g., "@param var color : Vec4" inside SRC), not the SRC field itself.
    HaxeFieldDeclaration field = PsiTreeUtil.getParentOfType(element, HaxeFieldDeclaration.class, false);
    while (field != null) {
      if (field.isStatic() && "SRC".equals(field.getName()) && field.getVarInit() != null) {
        HaxeClass enclosingClass = PsiTreeUtil.getParentOfType(field, HaxeClass.class, false);
        if (enclosingClass != null && HxslContextCache.isHxslSrcField(field, enclosingClass)) {
          return field;
        }
      }
      field = PsiTreeUtil.getParentOfType(field, HaxeFieldDeclaration.class, true);
    }
    return null;
  }

  @Nullable
  public static HaxeClassModel getEnclosingShaderClass(@NotNull PsiElement element) {
    HaxeFieldDeclaration field = getEnclosingHxslField(element);
    if (field == null) return null;

    HaxeClass enclosingClass = PsiTreeUtil.getParentOfType(field, HaxeClass.class, false);
    if (enclosingClass == null) return null;

    return enclosingClass.getModel();
  }

  public static boolean isInsideHxslBlock(@NotNull PsiElement element) {
    return getEnclosingHxslField(element) != null;
  }
}
