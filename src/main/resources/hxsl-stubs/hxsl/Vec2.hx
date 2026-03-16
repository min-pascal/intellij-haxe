package hxsl;

class Vec2 {
  public var x:Float;
  public var y:Float;

  public var xy(get, never):Vec2;
  private function get_xy():Vec2 return null;
}
