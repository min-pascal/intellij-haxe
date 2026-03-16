package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeClassBody;
import com.intellij.plugins.haxe.lang.psi.HaxeFieldDeclaration;
import com.intellij.plugins.haxe.lang.psi.HaxeVarInit;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HxslContextCache {

  private HxslContextCache() {
  }

  public static boolean isHxslSrcField(@NotNull HaxeFieldDeclaration field, @NotNull HaxeClass enclosingClass) {
    Set<HaxeFieldDeclaration> srcFields =
      CachedValuesManager.getProjectPsiDependentCache(enclosingClass, HxslContextCache::computeForClass);
    return srcFields.contains(field);
  }

  private static Set<HaxeFieldDeclaration> computeForClass(@NotNull HaxeClass haxeClass) {
    // Field declarations live under HaxeClassBody, not as direct children of HaxeClass
    HaxeClassBody classBody = PsiTreeUtil.getChildOfType(haxeClass, HaxeClassBody.class);
    if (classBody == null) {
      return Collections.emptySet();
    }

    // Collect candidate fields: static var SRC with an initializer
    Set<HaxeFieldDeclaration> candidates = new LinkedHashSet<>();
    List<HaxeFieldDeclaration> fields = classBody.getFieldDeclarationList();
    for (HaxeFieldDeclaration field : fields) {
      HaxeVarInit varInit = field.getVarInit();
      if (field.isStatic() && "SRC".equals(field.getName()) && varInit != null) {
        candidates.add(field);
      }
    }
    if (candidates.isEmpty()) {
      return Collections.emptySet();
    }

    // Resolve hxsl.Shader — if not on classpath, gracefully return empty
    HaxeClass shaderClass = HaxeResolveUtil.findClassByQName("hxsl.Shader", haxeClass);
    if (shaderClass == null) {
      return Collections.emptySet();
    }

    HaxeClassModel model = haxeClass.getModel();
    if (model != null && model.inheritsFrom(shaderClass)) {
      return candidates;
    }
    return Collections.emptySet();
  }
}
