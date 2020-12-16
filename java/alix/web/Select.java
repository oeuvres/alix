package alix.web;

import java.util.List;

public interface Select
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
  List<Select> list();
  
  public default String options()
  {
    StringBuilder sb = new StringBuilder();
    for (Select opt : list()) {
      String value = opt.toString();
      sb.append("<option");
      if (this == opt) sb.append(" selected=\"selected\"");
      sb.append(" value=\"");
      sb.append(value);
      sb.append("\">");
      sb.append(opt.label());
      sb.append("</option>\n");
    }
    return sb.toString();
  }
  
}
