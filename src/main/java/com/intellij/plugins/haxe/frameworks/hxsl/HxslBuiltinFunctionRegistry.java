package com.intellij.plugins.haxe.frameworks.hxsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class HxslBuiltinFunctionRegistry {

  private HxslBuiltinFunctionRegistry() {
  }

  public static final class HxslBuiltinFunction {
    public final String name;
    public final String signature;
    public final String returnType;

    HxslBuiltinFunction(String name, String signature, String returnType) {
      this.name = name;
      this.signature = signature;
      this.returnType = returnType;
    }

    public List<String> getParameterTypes() {
      int openParen = signature.indexOf('(');
      int closeParen = signature.indexOf(')');
      if (openParen < 0 || closeParen < 0 || closeParen <= openParen + 1) {
        return List.of();
      }
      String paramStr = signature.substring(openParen + 1, closeParen).trim();
      List<String> params = new ArrayList<>();
      for (String p : paramStr.split(",")) {
        String trimmed = p.trim();
        if (!trimmed.isEmpty()) {
          params.add(trimmed);
        }
      }
      return params;
    }
  }

  public static final List<HxslBuiltinFunction> FUNCTIONS = List.of(
    new HxslBuiltinFunction("vec2", "(Float, Float) -> Vec2", "Vec2"),
    new HxslBuiltinFunction("vec3", "(Float, Float, Float) -> Vec3", "Vec3"),
    new HxslBuiltinFunction("vec4", "(Float, Float, Float, Float) -> Vec4", "Vec4"),
    new HxslBuiltinFunction("mat3", "(Mat4) -> Mat3", "Mat3"),
    new HxslBuiltinFunction("normalize", "(VecN) -> VecN", "VecN"),
    new HxslBuiltinFunction("dot", "(VecN, VecN) -> Float", "Float"),
    new HxslBuiltinFunction("cross", "(Vec3, Vec3) -> Vec3", "Vec3"),
    new HxslBuiltinFunction("mix", "(VecN, VecN, Float) -> VecN", "VecN"),
    new HxslBuiltinFunction("clamp", "(Float, Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("saturate", "(Float) -> Float", "Float"),
    new HxslBuiltinFunction("length", "(VecN) -> Float", "Float"),
    new HxslBuiltinFunction("pow", "(Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("sqrt", "(Float) -> Float", "Float"),
    new HxslBuiltinFunction("abs", "(Float) -> Float", "Float"),
    new HxslBuiltinFunction("min", "(Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("max", "(Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("floor", "(Float) -> Float", "Float"),
    new HxslBuiltinFunction("ceil", "(Float) -> Float", "Float"),
    new HxslBuiltinFunction("fract", "(Float) -> Float", "Float"),
    new HxslBuiltinFunction("mod", "(Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("step", "(Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("smoothstep", "(Float, Float, Float) -> Float", "Float"),
    new HxslBuiltinFunction("texture", "(Sampler2D, Vec2) -> Vec4", "Vec4")
  );

  public static final Map<String, HxslBuiltinFunction> BY_NAME =
    FUNCTIONS.stream().collect(Collectors.toUnmodifiableMap(f -> f.name, f -> f));
}
