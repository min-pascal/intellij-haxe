/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
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
package com.intellij.plugins.haxe.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

/**
 * Utility methods including safe wrappers for Java-specific IntelliJ APIs
 * (PsiPackage, PackageIndex, JavaPsiFacade, JavaDirectoryService)
 * which are not available in non-Java IDEs like WebStorm.
 * All safe wrapper methods catch NoClassDefFoundError to prevent crashes.
 */
public class HaxeJavaUtil {
  @Nullable
  static public <T>T cast(Object a, Class<T> clazz) {
    if (a == null) return null;
    if (clazz.isAssignableFrom(a.getClass())) {
      return (T)a;
    }
    return null;
  }

  /**
   * Safely checks if a PsiElement is an instance of com.intellij.psi.PsiPackage.
   * Returns false when PsiPackage is not available (e.g. in WebStorm).
   */
  public static boolean isPsiPackage(@Nullable PsiElement element) {
    if (element == null) return false;
    try {
      return element instanceof com.intellij.psi.PsiPackage;
    } catch (NoClassDefFoundError e) {
      return false;
    }
  }

  /**
   * Safely checks if the given class is PsiPackage or assignable from it.
   * Returns false when PsiPackage is not available (e.g. in WebStorm).
   */
  public static boolean isPsiPackageClass(@Nullable Class<?> clazz) {
    if (clazz == null) return false;
    try {
      return com.intellij.psi.PsiPackage.class.isAssignableFrom(clazz);
    } catch (NoClassDefFoundError e) {
      return false;
    }
  }

  /**
   * Safely casts a PsiElement to PsiPackage. Returns null if not a PsiPackage
   * or when PsiPackage is not available (e.g. in WebStorm).
   */
  @Nullable
  public static com.intellij.psi.PsiPackage asPsiPackage(@Nullable PsiElement element) {
    if (element == null) return null;
    try {
      if (element instanceof com.intellij.psi.PsiPackage pkg) return pkg;
      return null;
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  /**
   * Safely calls JavaPsiFacade.getInstance(project).findPackage(qualifiedName).
   * Returns null when JavaPsiFacade/PsiPackage is not available (e.g. in WebStorm).
   */
  @Nullable
  public static com.intellij.psi.PsiPackage findPackage(@Nullable Project project, @Nullable String qualifiedName) {
    if (project == null || qualifiedName == null) return null;
    try {
      return com.intellij.psi.JavaPsiFacade.getInstance(project).findPackage(qualifiedName);
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  /**
   * Safely calls PackageIndex.getInstance(project).getPackageNameByDirectory(virtualFile).
   * Returns null when PackageIndex is not available (e.g. in WebStorm).
   */
  @Nullable
  public static String getPackageNameByDirectory(@Nullable Project project, @Nullable VirtualFile virtualFile) {
    if (project == null || virtualFile == null) return null;
    try {
      return com.intellij.openapi.roots.PackageIndex.getInstance(project).getPackageNameByDirectory(virtualFile);
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  /**
   * Safely gets qualified name from a PsiPackage element.
   * Returns null if element is not a PsiPackage or when not available.
   */
  @Nullable
  public static String getPackageQualifiedName(@Nullable PsiElement element) {
    if (element == null) return null;
    try {
      if (element instanceof com.intellij.psi.PsiPackage pkg) return pkg.getQualifiedName();
      return null;
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  /**
   * Safely gets sub-packages from a PsiPackage element.
   * Returns null if element is not a PsiPackage or when not available.
   */
  @Nullable
  public static com.intellij.psi.PsiPackage[] getSubPackages(@Nullable PsiElement element) {
    if (element == null) return null;
    try {
      if (element instanceof com.intellij.psi.PsiPackage pkg) return pkg.getSubPackages();
      return null;
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }

  /**
   * Safely gets PsiDirectory[] from a PsiPackage with the given scope.
   * Returns empty array if not a PsiPackage or when not available.
   */
  public static PsiDirectory[] getPackageDirectories(@Nullable PsiElement element, @Nullable GlobalSearchScope scope) {
    if (element == null) return new PsiDirectory[0];
    try {
      if (element instanceof com.intellij.psi.PsiPackage pkg) {
        return scope != null ? pkg.getDirectories(scope) : pkg.getDirectories();
      }
      return new PsiDirectory[0];
    } catch (NoClassDefFoundError e) {
      return new PsiDirectory[0];
    }
  }

  /**
   * Safely gets PsiFile[] from a PsiPackage with the given scope.
   * Returns empty array if not a PsiPackage or when not available.
   */
  public static PsiFile[] getPackageFiles(@Nullable PsiElement element, @Nullable GlobalSearchScope scope) {
    if (element == null) return new PsiFile[0];
    try {
      if (element instanceof com.intellij.psi.PsiPackage pkg) {
        return scope != null ? pkg.getFiles(scope) : pkg.getFiles(GlobalSearchScope.allScope(pkg.getProject()));
      }
      return new PsiFile[0];
    } catch (NoClassDefFoundError e) {
      return new PsiFile[0];
    }
  }

  /**
   * Safely calls JavaDirectoryService.getInstance().getPackage(directory).
   * Returns null when JavaDirectoryService is not available (e.g. in WebStorm).
   */
  @Nullable
  public static com.intellij.psi.PsiPackage getPackageForDirectory(@Nullable PsiDirectory directory) {
    if (directory == null) return null;
    try {
      return com.intellij.psi.JavaDirectoryService.getInstance().getPackage(directory);
    } catch (NoClassDefFoundError e) {
      return null;
    }
  }
}
