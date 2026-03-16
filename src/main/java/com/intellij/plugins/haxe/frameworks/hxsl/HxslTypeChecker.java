package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.plugins.haxe.ide.annotator.HaxeStandardAnnotation;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator;
import com.intellij.plugins.haxe.model.type.HaxeTypeResolver;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class HxslTypeChecker {

  private HxslTypeChecker() {
  }

  private static final Set<String> HXSL_TYPES = Set.of(
    "Vec2", "Vec3", "Vec4",
    "IVec2", "IVec3", "IVec4",
    "Mat2", "Mat3", "Mat3x4", "Mat4"
  );

  private static final String SWIZZLE_CHARS_2 = "xyrg";
  private static final String SWIZZLE_CHARS_3 = "xyzrgb";
  private static final String SWIZZLE_CHARS_4 = "xyzwrgba";

  public static boolean isHxslType(@Nullable String className) {
    return className != null && HXSL_TYPES.contains(className);
  }

  public static boolean isValidHxslExpression(@NotNull PsiElement element) {
    try {
      // Check 1: Built-in function calls
      if (element instanceof HaxeCallExpression callExpr) {
        HaxeExpression callee = callExpr.getExpression();
        if (callee != null && HxslBuiltinFunctionRegistry.BY_NAME.containsKey(callee.getText())) {
          return true;
        }
      }

      // Check 2: Swizzle access on vector types
      if (element instanceof HaxeReferenceExpression refExpr) {
        PsiElement qualifier = refExpr.getQualifier();
        if (qualifier != null) {
          ResultHolder holder = HaxeExpressionEvaluator.evaluate(qualifier, null).result;
          if (holder != null && !holder.isUnknown()) {
            SpecificHaxeClassReference classType = holder.getClassType();
            if (classType != null && isVecType(classType.getClassName())) {
              String memberName = refExpr.getReferenceName();
              if (memberName != null && isValidSwizzleForType(memberName, classType.getClassName())) {
                return true;
              }
            }
          }
        }
      }

      // Check 3: Arithmetic on HXSL vector/matrix types
      if (element instanceof HaxeBinaryExpression binExpr) {
        String op = binExpr.getOperator().getText();
        if ("+".equals(op) || "-".equals(op) || "*".equals(op) || "/".equals(op)) {
          HaxeExpression left = binExpr.getLeftExpression();
          HaxeExpression right = binExpr.getRightExpression();
          if (left != null && right != null) {
            String leftClass = resolveClassName(left);
            String rightClass = resolveClassName(right);
            if (leftClass != null && rightClass != null) {
              // Both same HXSL vector type
              if (isHxslType(leftClass) && leftClass.equals(rightClass)) return true;
              // Vec + Float or Float + Vec
              if (isVecType(leftClass) && "Float".equals(rightClass)) return true;
              if ("Float".equals(leftClass) && isVecType(rightClass)) return true;
              // Vec * Mat of matching dimension
              if (isVecMatMatch(leftClass, rightClass) || isVecMatMatch(rightClass, leftClass)) return true;
            }
          }
        }
      }
    }
    catch (Exception ignored) {
    }
    return false;
  }

  public static void checkAssignment(@NotNull HaxeAssignExpression assignExpression, @NotNull AnnotationHolder holder) {
    try {
      PsiElement lhs = UsefulPsiTreeUtil.getFirstChildSkipWhiteSpacesAndComments(assignExpression);
      PsiElement assignOp = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(lhs);
      PsiElement rhs = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(assignOp);
      if (lhs == null || rhs == null) return;

      ResultHolder lhsType = HaxeTypeResolver.getPsiElementType(lhs, assignExpression, null);
      ResultHolder rhsType = HaxeTypeResolver.getPsiElementType(rhs, assignExpression, null);
      if (lhsType == null || lhsType.isUnknown() || rhsType == null || rhsType.isUnknown()) return;

      SpecificHaxeClassReference lhsClassRef = lhsType.getClassType();
      SpecificHaxeClassReference rhsClassRef = rhsType.getClassType();
      if (lhsClassRef == null || rhsClassRef == null) return;

      String lhsClassName = lhsClassRef.getClassName();
      String rhsClassName = rhsClassRef.getClassName();
      if (!isHxslType(lhsClassName) || !isHxslType(rhsClassName)) return;
      if (lhsClassName.equals(rhsClassName)) return;

      HaxeStandardAnnotation.typeMismatch(holder, rhs, rhsClassName, lhsClassName).create();
    }
    catch (Exception ignored) {
    }
  }

  private static boolean isVecType(@Nullable String className) {
    if (className == null) return false;
    return switch (className) {
      case "Vec2", "Vec3", "Vec4", "IVec2", "IVec3", "IVec4" -> true;
      default -> false;
    };
  }

  private static boolean isValidSwizzleForType(@NotNull String name, @Nullable String className) {
    if (name.isEmpty() || name.length() > 4 || className == null) return false;
    String validChars = switch (className) {
      case "Vec2", "IVec2" -> SWIZZLE_CHARS_2;
      case "Vec3", "IVec3" -> SWIZZLE_CHARS_3;
      case "Vec4", "IVec4" -> SWIZZLE_CHARS_4;
      default -> null;
    };
    if (validChars == null) return false;
    for (int i = 0; i < name.length(); i++) {
      if (validChars.indexOf(name.charAt(i)) < 0) return false;
    }
    return true;
  }

  private static boolean isVecMatMatch(@Nullable String vecClass, @Nullable String matClass) {
    if (vecClass == null || matClass == null) return false;
    return ("Vec3".equals(vecClass) && "Mat3".equals(matClass))
      || ("Vec4".equals(vecClass) && "Mat4".equals(matClass));
  }

  @Nullable
  private static String resolveClassName(@NotNull PsiElement element) {
    try {
      ResultHolder holder = HaxeExpressionEvaluator.evaluate(element, null).result;
      if (holder == null || holder.isUnknown()) return null;
      SpecificHaxeClassReference classType = holder.getClassType();
      return classType != null ? classType.getClassName() : null;
    }
    catch (Exception e) {
      return null;
    }
  }
}
