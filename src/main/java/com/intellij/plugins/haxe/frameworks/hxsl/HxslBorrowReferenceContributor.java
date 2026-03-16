package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataContent;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataRunTimeMeta;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataType;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class HxslBorrowReferenceContributor extends PsiReferenceContributor {

  @Override
  public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(HaxeMetadataContent.class), new PsiReferenceProvider() {
      @Override
      public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                             @NotNull ProcessingContext context) {
        HaxeMetadataContent content = (HaxeMetadataContent) element;

        if (!HxslUtil.isInsideHxslBlock(content)) return PsiReference.EMPTY_ARRAY;

        HaxeMetadataRunTimeMeta meta = PsiTreeUtil.getParentOfType(content, HaxeMetadataRunTimeMeta.class);
        if (meta == null) return PsiReference.EMPTY_ARRAY;

        HaxeMetadataType metaType = meta.getType();
        if (metaType == null || !"borrow".equals(metaType.getText())) return PsiReference.EMPTY_ARRAY;

        String raw = content.getText();
        if (raw == null || raw.isEmpty()) return PsiReference.EMPTY_ARRAY;

        // Find inner content between parentheses, trimming whitespace
        int innerStart = 0;
        int innerEnd = raw.length();
        int parenOpen = raw.indexOf('(');
        int parenClose = raw.lastIndexOf(')');
        if (parenOpen >= 0 && parenClose > parenOpen) {
          innerStart = parenOpen + 1;
          innerEnd = parenClose;
        }
        // Trim leading whitespace
        while (innerStart < innerEnd && Character.isWhitespace(raw.charAt(innerStart))) {
          innerStart++;
        }
        // Trim trailing whitespace
        while (innerEnd > innerStart && Character.isWhitespace(raw.charAt(innerEnd - 1))) {
          innerEnd--;
        }
        if (innerStart >= innerEnd) return PsiReference.EMPTY_ARRAY;

        // Parse segments by scanning for '.' separators to preserve exact offsets
        List<String> segNames = new ArrayList<>();
        List<TextRange> segRanges = new ArrayList<>();
        int segStart = innerStart;
        for (int i = innerStart; i <= innerEnd; i++) {
          if (i == innerEnd || raw.charAt(i) == '.') {
            if (i > segStart) {
              // Trim leading whitespace from segment
              int trimLeft = segStart;
              while (trimLeft < i && Character.isWhitespace(raw.charAt(trimLeft))) {
                trimLeft++;
              }
              // Trim trailing whitespace from segment
              int trimRight = i;
              while (trimRight > trimLeft && Character.isWhitespace(raw.charAt(trimRight - 1))) {
                trimRight--;
              }
              if (trimLeft < trimRight) {
                segNames.add(raw.substring(trimLeft, trimRight));
                segRanges.add(TextRange.create(trimLeft, trimRight));
              }
            }
            segStart = i + 1;
          }
        }
        if (segNames.isEmpty()) return PsiReference.EMPTY_ARRAY;

        // Build one PsiReference per segment
        String[] parts = segNames.toArray(new String[0]);
        PsiReference[] refs = new PsiReference[parts.length];
        for (int i = 0; i < parts.length; i++) {
          final int idx = i;
          refs[i] = new PsiReferenceBase<PsiElement>(content, segRanges.get(i), true) {
            @Override
            public @Nullable PsiElement resolve() {
              try {
                if (idx == 0) {
                  HaxeClass haxeClass = HaxeResolveUtil.findClassByQName(parts[0], content);
                  return haxeClass != null ? haxeClass.getComponentName() : null;
                }
                else {
                  HaxeClass haxeClass = HaxeResolveUtil.findClassByQName(parts[0], content);
                  if (haxeClass == null) return null;

                  HaxeFieldDeclaration srcField = findSrcField(haxeClass);
                  if (srcField == null) return null;
                  HaxeVarInit varInit = srcField.getVarInit();
                  if (varInit == null) return null;

                  PsiElement currentScope = varInit;
                  PsiElement resolvedName = null;
                  for (int seg = 1; seg <= idx; seg++) {
                    String segName = parts[seg];
                    resolvedName = null;

                    PsiElement[] scopeChildren = currentScope.getChildren();
                    // Stage 1: direct children lookup
                    for (PsiElement child : scopeChildren) {
                      if (child instanceof HaxeLocalVarDeclaration varDecl) {
                        HaxeComponentName name = varDecl.getComponentName();
                        if (name != null && segName.equals(name.getText())) {
                          resolvedName = name;
                          HaxeVarInit nextInit = varDecl.getVarInit();
                          if (nextInit != null) {
                            currentScope = nextInit;
                          }
                          break;
                        }
                      }
                    }
                    if (resolvedName == null) {
                      for (PsiElement child : scopeChildren) {
                        if (child instanceof HaxeFieldDeclaration fieldDecl) {
                          HaxeComponentName name = fieldDecl.getComponentName();
                          if (name != null && segName.equals(name.getText())) {
                            resolvedName = name;
                            HaxeVarInit nextInit = fieldDecl.getVarInit();
                            if (nextInit != null) {
                              currentScope = nextInit;
                            }
                            break;
                          }
                        }
                      }
                    }
                    // Stage 2: recursive fallback over all descendants
                    if (resolvedName == null) {
                      for (HaxeLocalVarDeclaration varDecl : PsiTreeUtil.findChildrenOfType(currentScope, HaxeLocalVarDeclaration.class)) {
                        HaxeComponentName name = varDecl.getComponentName();
                        if (name != null && segName.equals(name.getText())) {
                          resolvedName = name;
                          HaxeVarInit nextInit = varDecl.getVarInit();
                          if (nextInit != null) {
                            currentScope = nextInit;
                          }
                          break;
                        }
                      }
                    }
                    if (resolvedName == null) {
                      for (HaxeFieldDeclaration fieldDecl : PsiTreeUtil.findChildrenOfType(currentScope, HaxeFieldDeclaration.class)) {
                        HaxeComponentName name = fieldDecl.getComponentName();
                        if (name != null && segName.equals(name.getText())) {
                          resolvedName = name;
                          HaxeVarInit nextInit = fieldDecl.getVarInit();
                          if (nextInit != null) {
                            currentScope = nextInit;
                          }
                          break;
                        }
                      }
                    }
                    if (resolvedName == null) return null;
                  }
                  return resolvedName;
                }
              }
              catch (Exception e) {
                return null;
              }
            }
          };
        }
        return refs;
      }

      @Nullable
      private HaxeFieldDeclaration findSrcField(@NotNull HaxeClass haxeClass) {
        HaxeClassBody classBody = PsiTreeUtil.getChildOfType(haxeClass, HaxeClassBody.class);
        if (classBody == null) return null;
        for (HaxeFieldDeclaration field : classBody.getFieldDeclarationList()) {
          if (field.isStatic() && "SRC".equals(field.getName()) && field.getVarInit() != null) {
            return field;
          }
        }
        return null;
      }
    });
  }
}
