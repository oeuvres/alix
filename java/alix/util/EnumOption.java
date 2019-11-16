package alix.util;

import java.util.List;

public interface EnumOption
{
  /**
   * Ensure that an option has a human label.
   */
  String label();
  /**
   * Get a cached non mutable Iterable list of all options.
   * Enum.values() is expensive, always cloning an array of all fields.
   * See <a href="https://github.com/ndru83/desugaring-java/blob/master/enum-internals.adoc">Java Enum Internals</a>.
   */
  List<EnumOption> list();
  
}
