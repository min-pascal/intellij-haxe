/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 TiVo Inc.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.lang.psi.*;

import com.intellij.psi.*;
import com.intellij.util.IncorrectOperationException;
import lombok.CustomLog;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * @author: Srikanth.Ganapavarapu
 */
@CustomLog
public abstract class HaxeParameterPsiMixinImpl extends AbstractHaxeNamedComponent implements HaxeParameterPsiMixin {


  public HaxeParameterPsiMixinImpl(ASTNode node) {
    super(node);
  }

  public HaxeParameterPsiMixinImpl(HaxeParameter parameter) {
    super(parameter.getNode());
  }

  @NotNull
  public PsiElement getDeclarationScope() {
    // Lifted, lock, stock, and barrel from PsiParameterImpl.java
    // which was for the Java language.
    // TODO:  Need to verify against the Haxe language spec.
    //              Are there other situations?
    final PsiElement parent = getParent();
    if (parent == null) return this;

    if (parent instanceof HaxeParameterListPsiMixin) {
      return parent.getParent();
    }

    if (parent instanceof HaxeCatchStatement) {
      return parent;
    }
    if (parent instanceof HaxeForStatement) {
      return parent;
    }

    PsiElement[] children = parent.getChildren();
    //noinspection ConstantConditions
    if (children != null) {
      ext:
      for (int i = 0; i < children.length; i++) {
        if (children[i].equals(this)) {
          for (int j = i + 1; j < children.length; j++) {
            if (children[j] instanceof HaxeCodeBlock) return children[j];
          }
          break ext;
        }
      }
    }

    log.error("Code block not found among parameter' (" + this + ") parent' (" + parent + ") children: " + Arrays.asList(children));
    return null;
  }

  public boolean isVarArgs() {
    // In Haxe (http://old.haxe.org/doc/cross/reflect), there are no
    // varargs parameters, but the function is made to accept variable
    // arguments.  So, at this level it's always false.
    return false;
  }

  @Nullable
  public Object computeConstantValue() {
    // XXX: this may need to be implemented for refactoring functionality
    return null;
  }

  @Nullable
  public PsiElement getNameIdentifier() {
    final HaxeComponentName componentName = getComponentName();
    return componentName != null ? componentName.getIdentifier() : null;
  }

  @NotNull
  @Override
  public HaxeModifierList getModifierList() {
    HaxeModifierList haxePsiModifierList = new HaxeModifierListImpl(this.getNode());

    // Triplicated code! HaxeMethodPsiMixinImpl + HaxeParameterPsiMixinImpl + HaxePsiFieldImpl
    if (isStatic()) {
      haxePsiModifierList.setModifierProperty(HaxePsiModifier.STATIC, true);
    }

    if (isInline()) {
      haxePsiModifierList.setModifierProperty(HaxePsiModifier.INLINE, true);
    }

    if (isPublic()) {
      haxePsiModifierList.setModifierProperty(HaxePsiModifier.PUBLIC, true);
    }
    else {
      haxePsiModifierList.setModifierProperty(HaxePsiModifier.PRIVATE, true);
    }

    // XXX: make changes to bnf, and add code to detect any other missing annotations/modifiers
    // that can be applied to an identifier declaration... set appropriate elements as above.
    // E.g. see AbstractHaxeClassPsi

    return haxePsiModifierList;
  }

  @Override
  public boolean hasModifierProperty(@HaxePsiModifier.ModifierConstant @NonNls @NotNull String name) {
    return getModifierList().hasModifierProperty(name);
  }

}
