package hxsl;

class Vec3 {
  public var x:Float;
  public var y:Float;
  public var z:Float;

  public var xy(get, never):Vec2;
  private function get_xy():Vec2 return null;

  public var xyz(get, never):Vec3;
  private function get_xyz():Vec3 return null;

  public var rgb(get, never):Vec3;
  private function get_rgb():Vec3 return null;

  public var r(get, never):Float;
  private function get_r():Float return 0;

  public var g(get, never):Float;
  private function get_g():Float return 0;

  public var b(get, never):Float;
  private function get_b():Float return 0;
}
