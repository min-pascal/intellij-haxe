package com.intellij.plugins.haxe.ide.annotator.color;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.plugins.haxe.frameworks.hxsl.HxslAnnotationRegistry;
import com.intellij.plugins.haxe.frameworks.hxsl.HxslBuiltinFunctionRegistry;
import com.intellij.plugins.haxe.frameworks.hxsl.HxslUtil;
import com.intellij.plugins.haxe.ide.highlight.HaxeSyntaxHighlighterColors;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataRunTimeMeta;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataType;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class HxslColorAnnotator implements Annotator {

  private static final Set<String> HXSL_ALL_TYPES = Set.of(
    "Vec2", "Vec3", "Vec4",
    "IVec2", "IVec3", "IVec4",
    "Mat2", "Mat3", "Mat3x4", "Mat4",
    "Float", "Int", "Bool", "Sampler2D", "SamplerCube"
  );

  @Override
  public void annotate(@NotNull PsiElement node, @NotNull AnnotationHolder holder) {
    if (!HxslUtil.isInsideHxslBlock(node)) return;

    if (node instanceof HaxeMetadataRunTimeMeta meta) {
      HaxeMetadataType metaType = meta.getType();
      if (metaType == null) return;
      String name = metaType.getText();
      if (HxslAnnotationRegistry.ANNOTATION_SET.contains(name)) {
        HaxeColorAnnotatorUtil.annotate(holder, metaType, HaxeSyntaxHighlighterColors.METADATA);
      }
    }
    else if (node instanceof HaxeLocalFunctionDeclaration funcDecl) {
      HaxeComponentName componentName = funcDecl.getComponentName();
      if (componentName == null) return;
      String name = componentName.getName();
      if ("vertex".equals(name) || "fragment".equals(name)) {
        HaxeColorAnnotatorUtil.annotate(holder, componentName, HaxeSyntaxHighlighterColors.INSTANCE_MEMBER_FUNCTION);
      }
    }
    else if (node instanceof HaxeCallExpression callExpr) {
      HaxeExpression callee = callExpr.getExpression();
      if (callee instanceof HaxeReferenceExpression refExpr && refExpr.getQualifier() == null) {
        String name = refExpr.getText();
        if (HxslBuiltinFunctionRegistry.BY_NAME.containsKey(name)) {
          HaxeColorAnnotatorUtil.annotate(holder, refExpr, HaxeSyntaxHighlighterColors.STATIC_MEMBER_FUNCTION);
        }
      }
    }
    else if (node instanceof HaxeIdentifier identifier) {
      String text = identifier.getText();
      if (HXSL_ALL_TYPES.contains(text)) {
        PsiElement parent = identifier.getParent();
        if (parent instanceof HaxeReferenceExpression refExpr
          && !(refExpr.getParent() instanceof HaxeCallExpression)) {
          HaxeColorAnnotatorUtil.annotate(holder, identifier, HaxeSyntaxHighlighterColors.CLASS);
        }
      }
    }
  }
}
