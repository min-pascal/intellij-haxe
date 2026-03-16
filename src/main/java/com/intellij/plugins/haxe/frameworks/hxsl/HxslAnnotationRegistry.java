package com.intellij.plugins.haxe.frameworks.hxsl;

import java.util.List;
import java.util.Set;

public final class HxslAnnotationRegistry {

  private HxslAnnotationRegistry() {
  }

  public static final List<String> ANNOTATIONS = List.of(
    "input",
    "param",
    "global",
    "var",
    "const",
    "borrow",
    "perInstance",
    "flat"
  );

  public static final Set<String> ANNOTATION_SET = Set.of(
    "input",
    "param",
    "global",
    "var",
    "const",
    "borrow",
    "perInstance",
    "flat"
  );
}
