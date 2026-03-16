package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.ConstantNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.HaxeBlockStatement;
import com.intellij.plugins.haxe.lang.psi.HaxeComponentName;
import com.intellij.plugins.haxe.lang.psi.HaxeReferenceExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeType;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataRunTimeMeta;
import com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class HxslCompletionContributor extends CompletionContributor {

  private static final InsertHandler<LookupElement> FUNCTION_INSERT_HANDLER = (context, item) -> {
    String fnName = item.getLookupString();
    HxslBuiltinFunctionRegistry.HxslBuiltinFunction fn = HxslBuiltinFunctionRegistry.BY_NAME.get(fnName);
    if (fn == null) return;

    List<String> paramTypes = fn.getParameterTypes();
    Editor editor = context.getEditor();

    if (paramTypes.isEmpty()) {
      // No parameters — just insert "()"
      context.getDocument().insertString(context.getTailOffset(), "()");
      editor.getCaretModel().moveToOffset(context.getTailOffset() + 2);
      return;
    }

    // Build a live template with tab-stop placeholders for each parameter
    TemplateManager templateManager = TemplateManager.getInstance(context.getProject());
    Template template = templateManager.createTemplate("", "");
    template.addTextSegment("(");
    for (int i = 0; i < paramTypes.size(); i++) {
      if (i > 0) template.addTextSegment(", ");
      template.addVariable("param" + i, new ConstantNode(paramTypes.get(i)), true);
    }
    template.addTextSegment(")");
    template.setToReformat(false);

    templateManager.startTemplate(editor, template);
  };

  private static final List<String> SWIZZLES_2 = List.of("x", "y", "r", "g", "xy", "rg");
  private static final List<String> SWIZZLES_3 = List.of("x", "y", "z", "r", "g", "b", "xy", "xyz", "rg", "rgb");
  private static final List<String> SWIZZLES_4 = List.of("x", "y", "z", "w", "r", "g", "b", "a", "xy", "xyz", "xyzw", "rg", "rgb", "rgba");

  public HxslCompletionContributor() {
    // Provider 1 — Annotation Completion
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(HaxeTokenTypes.META_ID),
      new CompletionProvider<>() {
        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
          if (!HxslUtil.isInsideHxslBlock(parameters.getPosition())) return;
          for (String name : HxslAnnotationRegistry.ANNOTATIONS) {
            result.addElement(LookupElementBuilder.create(name).withTypeText("HXSL annotation", true));
          }
        }
      });

    // Provider 2 — Built-in Function Completion
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(),
      new CompletionProvider<>() {
        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
          PsiElement position = parameters.getPosition();
          if (!HxslUtil.isInsideHxslBlock(position)) return;
          // Skip metadata, type-tag/type-reference, and dot-member-access contexts
          if (PsiTreeUtil.getParentOfType(position, HaxeMetadataRunTimeMeta.class) != null) return;
          if (PsiTreeUtil.getParentOfType(position, HaxeType.class) != null) return;
          HaxeReferenceExpression enclosingRef = PsiTreeUtil.getParentOfType(position, HaxeReferenceExpression.class, false);
          if (enclosingRef != null && enclosingRef.getQualifier() != null) return;
          if (PsiTreeUtil.getParentOfType(position, HaxeBlockStatement.class) == null) return;
          if (PsiTreeUtil.getParentOfType(position, HaxeComponentName.class, false) != null) return;
          for (HxslBuiltinFunctionRegistry.HxslBuiltinFunction fn : HxslBuiltinFunctionRegistry.FUNCTIONS) {
            result.addElement(LookupElementBuilder.create(fn.name)
              .withTailText(fn.signature.substring(fn.signature.indexOf("(")), true)
              .withTypeText(fn.returnType, true)
              .withInsertHandler(FUNCTION_INSERT_HANDLER));
          }
        }
      });

    // Provider 3 — Swizzle Completion
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(),
      new CompletionProvider<>() {
        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
          if (!HxslUtil.isInsideHxslBlock(parameters.getPosition())) return;
          try {
            PsiElement position = parameters.getPosition();
            HaxeReferenceExpression ref = PsiTreeUtil.getParentOfType(position, HaxeReferenceExpression.class, false);
            if (ref == null) return;
            PsiElement qualifier = ref.getQualifier();
            if (qualifier == null) return;
            ResultHolder holder = HaxeExpressionEvaluator.evaluate(qualifier, null).result;
            if (holder == null || holder.isUnknown()) return;
            SpecificHaxeClassReference classType = holder.getClassType();
            if (classType == null) return;
            String className = classType.getClassName();

            List<String> swizzles = getSwizzlesForClass(className);
            if (swizzles == null) return;

            for (String swizzle : swizzles) {
              String returnType = switch (swizzle.length()) {
                case 1 -> "Float";
                case 2 -> "Vec2";
                case 3 -> "Vec3";
                case 4 -> "Vec4";
                default -> "Float";
              };
              result.addElement(LookupElementBuilder.create(swizzle).withTypeText(returnType, true));
            }
          }
          catch (Exception ignored) {
          }
        }
      });
  }

  private static List<String> getSwizzlesForClass(String className) {
    if (className == null) return null;
    return switch (className) {
      case "Vec2", "IVec2" -> SWIZZLES_2;
      case "Vec3", "IVec3" -> SWIZZLES_3;
      case "Vec4", "IVec4" -> SWIZZLES_4;
      default -> null;
    };
  }
}
