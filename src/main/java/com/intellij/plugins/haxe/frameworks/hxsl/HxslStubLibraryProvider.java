package com.intellij.plugins.haxe.frameworks.hxsl;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class HxslStubLibraryProvider extends AdditionalLibraryRootsProvider {

  @Override
  public @NotNull Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
    // Detect real Heaps by scanning project library table directly for hxsl/Shader.hx.
    // IMPORTANT: Do NOT use HaxeResolveUtil, OrderEnumerator, or any root-enumerating API here —
    // they trigger getAdditionalProjectLibraries again, causing infinite recursion.
    if (hasRealHeapsOnClasspath(project)) {
      return Collections.emptyList();
    }

    URL resourceUrl = HxslStubLibraryProvider.class.getResource("/hxsl-stubs");
    if (resourceUrl == null) {
      return Collections.emptyList();
    }

    VirtualFile stubsRoot = VfsUtil.findFileByURL(resourceUrl);
    if (stubsRoot == null) {
      return Collections.emptyList();
    }

    return List.of(SyntheticLibrary.newImmutableLibrary(List.of(stubsRoot)));
  }

  private static boolean hasRealHeapsOnClasspath(@NotNull Project project) {
    try {
      // Use the project-level library table directly — this does NOT trigger AdditionalLibraryRootsProvider
      LibraryTable libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project);
      for (Library library : libraryTable.getLibraries()) {
        for (VirtualFile root : library.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES)) {
          VirtualFile shaderFile = root.findFileByRelativePath("hxsl/Shader.hx");
          if (shaderFile != null) {
            return true;
          }
        }
      }
    }
    catch (Exception ignored) {
    }
    return false;
  }
}
