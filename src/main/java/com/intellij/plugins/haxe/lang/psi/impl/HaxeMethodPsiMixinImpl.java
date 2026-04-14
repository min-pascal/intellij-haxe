/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 TiVo Inc.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018 Ilya Malanin
 * Copyright 2018 Eric Bishton
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
import com.intellij.openapi.diagnostic.LogLevel;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.HaxeEnumValueConstructorModel;
import com.intellij.plugins.haxe.model.HaxeMethodModel;

import com.intellij.plugins.haxe.model.HaxeParameterModel;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;


/**
 * @author: Srikanth.Ganapavarapu
 */
@CustomLog
public abstract class HaxeMethodPsiMixinImpl extends AbstractHaxeNamedComponent implements HaxeMethodPsiMixin {

  // TODO: Merge this PsiMixin class(and interface) with HaxeMethod.  There is no reason to keep both, or that this be named 'mixin'.

  static {
    log.info("Loaded HaxeMethodPsiMixinImpl");
    log.setLevel(LogLevel.DEBUG);
  }

  protected HaxeMethodPsiMixinImpl(ASTNode node) {
    super(node);
  }

  @Override
  @NotNull
  @NonNls
  public String getName() {
    final String name = super.getName();

    if (name == null) {
      PsiElement child = this.getFirstChild();
      while (child != null) {
        if (child instanceof HaxePsiToken && child.getText().equals(HaxeTokenTypes.ONEW.toString())) {
          return child.getText();
        }
        child = child.getNextSibling();
      }
    }

    return (name != null) ? name : "<unnamed>";
  }

  private HaxeMethodModel _model = null;
  public HaxeMethodModel getModel() {
    if (_model == null) {
      if (this instanceof  HaxeEnumValueDeclarationConstructor constructor) {
        _model = new HaxeEnumValueConstructorModel(constructor);
      }else {
        _model = new HaxeMethodModel((HaxeMethodImpl)this);
      }
    }
    return _model;
  }

  @Nullable
  public HaxeReturnStatement getReturnStatement() {
    // Not all function types have one of these...  If they do, the
    // subclass (via the generator) will override this method.
    return findChildByClass(HaxeReturnStatement.class);
  }


  @Nullable
  public HaxeTypeTag getTypeTag() {
    // Not all function types have one of these...  If they do, the
    // subclass (via the generator) will override this method.
    return findChildByClass(HaxeTypeTag.class);
  }


  @Nullable
  public HaxeThrowStatement getThrowStatement() {
    // Not all function types have one of these...  If they do, the
    // subclass (via the generator) will override this method.
    return null;
  }

  @Nullable
  public HaxeBlockStatement getBlockStatement() {
    // Not all function types have one of these...  If they do, the
    // subclass (via the generator) will override this method.
    return null;
  }

  public boolean isConstructor() {
    String name = getName();
    return name != null && name.equals(HaxeTokenTypes.ONEW.toString());
  }

  @Nullable
  public PsiComment getDocComment() {
    return HaxeResolveUtil.findDocumentation(this);
  }

  public boolean isVarArgs() {
    // In Haxe, the method is set to VarArgs at runtime, via a function call. (Reflect.makeVarArgs)
    // We would need the ability to know if a particular run sequence has
    // called such a function.  I don't think we can pull that off without
    // the compiler's help.
    // TODO: Use compiler completion to detect variable arguments usage.
    /*
        class Test {
        static function _foo(args:Array<Dynamic>)
            {
            return "Called with: " + args.join(", ");
        }

        static var foo:Dynamic = Reflect.makeVarArgs(_foo);

        static function main() {
            trace("Haxe is great!");
            trace(foo(1));
            trace(foo(1, 2));
            trace(foo(1, 2, 3));
        }
    }
     */


   // haxe 4.2 supports rest arguments
    for (HaxeParameterModel parameter : getModel().getParameters()) {
      if(parameter.isRest()) return true;
    }
    return false;
  }

  public boolean isDeprecated() {
    return false;
  }

  @Nullable
  public HaxeClass getContainingClass() {
    return PsiTreeUtil.getParentOfType(this, HaxeClass.class, true);
  }

  @Nullable
  public PsiElement getNameIdentifier() {
    final HaxeComponentName componentName = getComponentName();
    return componentName != null ? componentName.getIdentifier() : null;
  }

  @NotNull
  @Override
  public HaxeModifierList getModifierList() {

    //
    // Note Haxe's rules for visibility:
    // (from http://haxe.org/manual/class-field-visibility.html)
    //
    // Omitting the visibility modifier usually defaults the visibility to private,
    // but there are exceptions where it becomes public instead:
    //
    // - If the class is declared as extern.
    // - If the field id declared on an interface.
    // - If the field overrides a public field.
    //
    // Trivia: Protected
    //
    //   Haxe has no notion of a protected keyword known from Java, C++ and
    //   other object-oriented languages. However, its private behavior is
    //   equal to those language's protected behavior, so Haxe actually
    //   lacks their real private behavior.
    //

    HaxeModifierList list = super.getModifierList();

    if (null == list) {
      list = new HaxeModifierListImpl(this.getNode());
    }

    // -- below modifiers need to be set individually
    //    because, they cannot be enforced through macro-list

    if (super.isStatic()) {
      list.setModifierProperty(HaxePsiModifier.STATIC, true);
    }

    if (super.isPublic()) {
      list.setModifierProperty(HaxePsiModifier.PUBLIC, true);
    }
    else {
      list.setModifierProperty(HaxePsiModifier.PRIVATE, true);
    }

    return list;
  }

  public boolean hasModifierProperty(@HaxePsiModifier.ModifierConstant @NonNls @NotNull String name) {
    return this.getModifierList().hasModifierProperty(name);
  }

  @NotNull
  public HaxeParameterList getParameterList() {
    final HaxeParameterList list = PsiTreeUtil.getChildOfType(this, HaxeParameterList.class);
    return ((list != null) ? list : new HaxeParameterListImpl(new HaxeDummyASTNode("Dummy parameter list", getProject())));
  }

  @NotNull
  @Override
  public SearchScope getUseScope() {
    if(this instanceof HaxeLocalFunctionDeclaration) {
      final PsiElement outerBlock = UsefulPsiTreeUtil.getParentOfType(this, HaxeBlockStatement.class);
      if(outerBlock != null) {
        return new LocalSearchScope(outerBlock);
      }
    }
    return super.getUseScope();
  }

  @Nullable
  public abstract HaxeGenericParam getGenericParam();
}
