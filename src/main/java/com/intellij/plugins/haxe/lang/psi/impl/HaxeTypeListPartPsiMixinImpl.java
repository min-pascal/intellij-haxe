/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
import com.intellij.openapi.diagnostic.LogLevel;
import com.intellij.openapi.util.Pair;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiSuperMethodImplUtil;
import com.intellij.psi.impl.source.tree.java.PsiTypeParameterImpl;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import lombok.CustomLog;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Created by ebishton on 10/22/14.
 */
@CustomLog
public class HaxeTypeListPartPsiMixinImpl extends HaxePsiCompositeElementImpl implements HaxeTypeListPartPsiMixin {

  // XXX: TypeListPart might be better just being a private entry in the BNF.  I'm not sure.
  //
  // The thing I most dislike about subclassing PsiTypeParameter is that it implements
  // the PsiClass interface.  One of the (three possible)child elements that this class
  // holds is HaxeAnonymousType, which itself derives from HaxeClass (thus, PsiClass).
  // So, I'd prefer that HaxeTypePsiMixin implement the class interface.  Then we
  // would have better balance between the child implementations (and less special code)
  // and we can cleanly ignore this class in the hierarchy.
  //
  // The third child type that we can hold is HaxeFunctionType, which is simply a
  // construct for holding (effectively) a typedef, described via the arrow notation:
  //   type -> type -> return_type
  // This type doesn't really support a class, just gives a function pointer.  So, now
  // we've got a mostly empty stub class as well...

  static { log.setLevel(LogLevel.DEBUG); }


  // The child type that implements an interface for one of these three parameter types.
  // We can't assign this type in the constructor because the children aren't known
  // (to us) at construction time.  We'll check it later when one of the interfaces
  // are hit.
  HaxeClass myChildClass = null;

  HaxeTypeListPartPsiMixinImpl(ASTNode node) {
    super(node);
  }

  @NotNull
  private HaxeClass getDelegate() {
    // We're going to try to cache our child.  If the code changes, then this reference
    // may refer to the wrong class.  In that case, it would be  better to do the
    // lookup (resolveHaxeClass()), which has a caching algorithm of its own and
    // deals with naming changes accordingly.

    if (null == myChildClass) {
      PsiElement child = getFirstChild();
      PsiElement target = null;
      ASTNode node = child.getNode();
      if ( null != node ) {

        IElementType type = node.getElementType();
        if (HaxeTokenTypes.TYPE_OR_ANONYMOUS.equals(type)) {

          target = child.getFirstChild();
          IElementType targetType = target.getNode().getElementType();

          if (HaxeTokenTypes.TYPE.equals(targetType)) {
            HaxeType haxeType = (HaxeType) target;
            HaxeClass resolved = haxeType.getReferenceExpression().resolveHaxeClass().getHaxeClass();
            myChildClass = resolved != null ? resolved : AbstractHaxePsiClass.createEmptyFacade(getProject());
          } else if (HaxeTokenTypes.ANONYMOUS_TYPE.equals(targetType)){
            myChildClass = (HaxeAnonymousType) target;
          } else {
            log.debug("Target: " + target.toString());
            log.assertTrue(false, "Unexpected token type for child of TYPE_OR_ANONYMOUS");
            myChildClass = AbstractHaxePsiClass.createEmptyFacade(getProject());
          }
        } else if (HaxeTokenTypes.FUNCTION_TYPE.equals(type)) {
          // FIXME: Temporary Hack to get around an unexpected PSI state #627.
          try {
            myChildClass = (HaxeAnonymousType) child;
          }
          catch(ClassCastException e) {
            log.warn("Unexpected PSI state.  See issue #627");
            myChildClass = AbstractHaxePsiClass.createEmptyFacade(getProject());
          }
        } else {
          myChildClass = AbstractHaxePsiClass.createEmptyFacade(getProject());
        }
      } else {
        // No child node?  How can this be?
        log.assertTrue(false, "No child node found for TYPE_LIST_PART");
        myChildClass = AbstractHaxePsiClass.createEmptyFacade(getProject());
      }
    }
    return myChildClass;

  }


  //
  // PsiTypeParameter overrides
  //

  @Override
  public PsiTypeParameterListOwner getOwner() {
    final PsiElement parent = getParent();
    if (parent == null) throw new PsiInvalidElementAccessException(this);
    return PsiTreeUtil.getParentOfType(this, PsiTypeParameterListOwner.class);
  }

  @Override
  public int getIndex() {
    int ret = 0;
    PsiElement element = getPrevSibling();
    while (element != null) {
      if (element instanceof PsiTypeParameter) {
        ret++;
      }
      element = element.getPrevSibling();
    }
    return ret;
  }


  //
  // PsiClass overrides
  //

  @Nullable
  @Override
  public String getQualifiedName() {
    return getDelegate().getQualifiedName();
  }

  @Override
  public boolean isInterface() {
    return getDelegate().isInterface();
  }

  @Override
  public boolean isAnnotationType() {
    return getDelegate().isAnnotationType();
  }

  @Override
  public boolean isEnum() {
    return getDelegate().isEnum();
  }

  @Override
  @NotNull
  public PsiField[] getFields() {
    return PsiField.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiMethod[] getMethods() {
    return PsiMethod.EMPTY_ARRAY;
  }

  @Override
  public PsiMethod findMethodBySignature(PsiMethod patternMethod, boolean checkBases) {
    return null;
  }

  @Override
  @NotNull
  public PsiMethod[] findMethodsBySignature(PsiMethod patternMethod, boolean checkBases) {
    return PsiMethod.EMPTY_ARRAY;
  }

  @Override
  public PsiField findFieldByName(String name, boolean checkBases) {
    return null;
  }

  @Override
  @NotNull
  public PsiMethod[] findMethodsByName(String name, boolean checkBases) {
    return PsiMethod.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public List<Pair<PsiMethod, PsiSubstitutor>> findMethodsAndTheirSubstitutorsByName(String name, boolean checkBases) {
    return new ArrayList<>();
  }

  @Override
  @NotNull
  public List<Pair<PsiMethod, PsiSubstitutor>> getAllMethodsAndTheirSubstitutors() {
    return new ArrayList<>();
  }

  @Override
  public PsiClass findInnerClassByName(String name, boolean checkBases) {
    return null;
  }

  @Override
  public PsiTypeParameterList getTypeParameterList() {
    return null;
  }

  @Override
  public boolean hasTypeParameters() {
    return getDelegate().isGeneric();
  }

  @Override
  public PsiElement getScope() {
    return getDelegate().getScope();
  }

  @Override
  public boolean isInheritorDeep(PsiClass baseClass, PsiClass classToByPass) {
    return false;
  }

  @Override
  public boolean isInheritor(@NotNull PsiClass baseClass, boolean checkDeep) {
    return false;
  }

  @Override
  @Nullable
  public PsiIdentifier getNameIdentifier() {
    return getDelegate().getNameIdentifier();
  }

  @Override
  public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
    return getDelegate().setName(name);
  }

  @Override
  @NotNull
  public PsiMethod[] getConstructors() {
    return PsiMethod.EMPTY_ARRAY;
  }

  @Override
  public PsiDocComment getDocComment() {
    return null;
  }

  @Override
  public boolean isDeprecated() {
    return getDelegate().isDeprecated();
  }

  @Override
  @NotNull
  public PsiReferenceList getExtendsList() {
    // Type parameters don't have extends lists in Haxe
    return null;
  }

  @Override
  public PsiReferenceList getImplementsList() {
    return null;
  }

  @Override
  @NotNull
  public PsiClassType[] getExtendsListTypes() {
    return PsiClassType.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiClassType[] getImplementsListTypes() {
    return PsiClassType.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiClass[] getInnerClasses() {
    return PsiClass.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiField[] getAllFields() {
    return PsiField.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiMethod[] getAllMethods() {
    return PsiMethod.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiClass[] getAllInnerClasses() {
    return PsiClass.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiClassInitializer[] getInitializers() {
    return PsiClassInitializer.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiTypeParameter[] getTypeParameters() {
    return PsiTypeParameter.EMPTY_ARRAY;
  }

  @Override
  public PsiClass getSuperClass() {
    return null;
  }

  @Override
  public PsiClass[] getInterfaces() {
    return PsiClass.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiClass[] getSupers() {
    return PsiClass.EMPTY_ARRAY;
  }

  @Override
  @NotNull
  public PsiClassType[] getSuperTypes() {
    return PsiClassType.EMPTY_ARRAY;
  }

  @Override
  public PsiClass getContainingClass() {
    return null;
  }

  @Override
  @NotNull
  public Collection<HierarchicalMethodSignature> getVisibleSignatures() {
    return Collections.emptyList();
  }

  @Override
  public HaxeModifierList getModifierList() {
    return getDelegate().getModifierList();
  }

  @Override
  public boolean hasModifierProperty(@NotNull String name) {
    return getDelegate().hasModifierProperty(name);
  }

  @Override
  public PsiJavaToken getLBrace() {
    PsiElement lBrace = getDelegate().getLBrace();
    return lBrace instanceof PsiJavaToken ? (PsiJavaToken) lBrace : null;
  }

  @Override
  public PsiJavaToken getRBrace() {
    PsiElement rBrace = getDelegate().getRBrace();
    return rBrace instanceof PsiJavaToken ? (PsiJavaToken) rBrace : null;
  }

  //
  // PsiAnnotationOwner
  //

  @Override
  @NotNull
  public PsiAnnotation[] getAnnotations() {
    // Type parameters don't get modifiers.
    return PsiAnnotation.EMPTY_ARRAY;
  }

  @Override
  public PsiAnnotation findAnnotation(@NotNull @NonNls String qualifiedName) {
    // Type parameters don't get modifiers.
    return null;
  }

  @Override
  @NotNull
  public PsiAnnotation addAnnotation(@NotNull @NonNls String qualifiedName) {
    throw new IncorrectOperationException();
  }

  @Override
  @NotNull
  public PsiAnnotation[] getApplicableAnnotations() {
    return getAnnotations();
  }


}
