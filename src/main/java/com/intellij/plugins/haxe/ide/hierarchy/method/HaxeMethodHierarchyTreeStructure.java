/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2019 Eric Bishton
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
package com.intellij.plugins.haxe.ide.hierarchy.method;

import com.intellij.ide.hierarchy.HierarchyBrowserManager;
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor;
import com.intellij.ide.hierarchy.HierarchyTreeStructure;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.ide.hierarchy.HaxeHierarchyUtils;
import com.intellij.plugins.haxe.ide.index.HaxeInheritanceDefinitionsUtil;
import com.intellij.plugins.haxe.lang.psi.HaxeAnonymousType;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxePsiModifier;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.FunctionalExpressionSearch;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by ebishton on 11/1/14.
 */
public class HaxeMethodHierarchyTreeStructure extends HierarchyTreeStructure {

  SmartPsiElementPointer<PsiMethod> myMethod;

  public HaxeMethodHierarchyTreeStructure(@NotNull Project project, @NotNull PsiMethod method) {
    super(project, new HaxeMethodHierarchyNodeDescriptor(project, null, method.getContainingClass(), true /*is_base*/, null));
    ((HaxeMethodHierarchyNodeDescriptor)getBaseDescriptor()).setTreeStructure(this);
    myMethod = SmartPointerManager.getInstance(myProject).createSmartPsiElementPointer(method);
    setBaseElement(myBaseDescriptor); //to set myRoot
  }

  // This is really the only thing we were overriding from MethodHierarchyTreeStructure.
  public static List<HaxeClass> getSubclasses(HaxeClass theClass) {
    final List<HaxeClass> subClasses = HaxeInheritanceDefinitionsUtil.getItemsByQNameIncludingSubChildren(theClass);
    return subClasses;
  }

  //
  // Most of the rest of this file was lifted from MethodHierarchyTreeStructure.java
  // because we couldn't override the private getSubclasses(), and because
  // buildChildren was final in that class. :/
  //

  @Nullable
  public final PsiMethod getBaseMethod() {
    final PsiElement element = myMethod.getElement();
    return element instanceof PsiMethod ? (PsiMethod)element : null;
  }

  @NotNull
  @Override
  protected Object[] buildChildren(@NotNull final HierarchyNodeDescriptor descriptor) {

    final HaxeClass theHaxeClass = ((HaxeMethodHierarchyNodeDescriptor) descriptor).getHaxeClass();
    if (null == theHaxeClass) return ArrayUtil.EMPTY_OBJECT_ARRAY;

    if (theHaxeClass instanceof HaxeAnonymousType) return ArrayUtil.EMPTY_OBJECT_ARRAY;
    if (theHaxeClass.hasModifierProperty(HaxePsiModifier.FINAL_META)) return ArrayUtil.EMPTY_OBJECT_ARRAY;

    final Collection<HaxeClass> subclasses = getSubclasses(theHaxeClass);

    final List<HierarchyNodeDescriptor> descriptors = new ArrayList<HierarchyNodeDescriptor>(subclasses.size());
    // Cast 5 fix: pass HaxeClass directly; shouldHideClass now accepts PsiElement
    for (final HaxeClass aClass : subclasses) {
      if (HierarchyBrowserManager.getInstance(myProject).getState().HIDE_CLASSES_WHERE_METHOD_NOT_IMPLEMENTED) {
        if (shouldHideClass(aClass)) {
          continue;
        }
      }

      final HaxeMethodHierarchyNodeDescriptor d = new HaxeMethodHierarchyNodeDescriptor(myProject, descriptor, aClass, false, this);
      descriptors.add(d);
    }

    // Cast 6 fix: guard with instanceof instead of casting through Object
    PsiMethod existingMethod = null;
    if (theHaxeClass instanceof PsiClass psiClass) {
      existingMethod = ((HaxeMethodHierarchyNodeDescriptor)descriptor).getMethod(psiClass, false);
    }
    if (existingMethod != null && !existingMethod.hasModifierProperty(HaxePsiModifier.FINAL)) {
      FunctionalExpressionSearch.search(existingMethod).forEach(expression -> {
        descriptors.add(new HaxeMethodHierarchyNodeDescriptor(myProject, descriptor, expression, false, HaxeMethodHierarchyTreeStructure.this));
        return true;
      });
    }

    return descriptors.toArray(new HierarchyNodeDescriptor[0]);
  }


  private boolean shouldHideClass(final PsiElement element) {
    if (getMethod(element, false) != null || isSuperClassForBaseClass(element)) {
      return false;
    }

    if (hasBaseClassMethod(element) || (element instanceof PsiModifierListOwner owner && isAbstract(owner))) {
      if (element instanceof HaxeClass haxeClass) {
        for (final HaxeClass subclass : getSubclasses(haxeClass)) {
          if (!shouldHideClass(subclass)) {
            return false;
          }
        }
      }
      return true;
    }

    return false;
  }

  private boolean isAbstract(final PsiModifierListOwner owner) {
    return owner.hasModifierProperty(HaxePsiModifier.ABSTRACT);
  }

  private boolean hasBaseClassMethod(final PsiElement element) {
    final PsiMethod baseClassMethod = getMethod(element, true);
    return baseClassMethod != null && !isAbstract(baseClassMethod);
  }

  private PsiMethod getMethod(final PsiElement element, final boolean checkBases) {
    if (element instanceof PsiClass aClass) {
      return HaxeHierarchyUtils.findBaseMethodInClass(getBaseMethod(), aClass, checkBases);
    }
    return null;
  }

  boolean isSuperClassForBaseClass(final PsiElement element) {
    if (!(element instanceof PsiClass aClass)) return false;
    final PsiMethod baseMethod = getBaseMethod();
    if (baseMethod == null) {
      return false;
    }
    final PsiClass baseClass = baseMethod.getContainingClass();
    if (baseClass == null) {
      return false;
    }
    // NB: parameters here are at CORRECT places!!!
    return baseClass.isInheritor(aClass, true);
  }


}
