package alix.web;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public interface Option
{
  /**
   * Ensure that an option has a human label.
   */
  public String label();
  /**
   * Optional hint
   */
  public String hint();

  public default String options() {
    return options(null);
  }

  public default String options(String filter)
  {
    Set<String> set = null;
    if (filter != null && !filter.isBlank()) {
      String[] values = filter.split("\\s+");
      if (values.length > 0) set = new HashSet<String>(Arrays.asList(values));
    }
    
    StringBuilder sb = new StringBuilder();
    for (Field f: this.getClass().getFields()) {
      if (!f.isEnumConstant()) continue;
      try {
        Option option = (Option) f.get(null);
        String value = option.toString();
        if (set != null && set.contains(value)) continue;
        sb.append("<option");
        if (this == option) sb.append(" selected=\"selected\"");
        sb.append(" value=\"").append(value).append("\"");
        if (hint() != null) sb.append(" title=\"").append(option.hint()).append("\"");
        sb.append(">");
        sb.append(option.label());
        sb.append("</option>\n");
      } catch (IllegalArgumentException | IllegalAccessException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    return sb.toString();
  }
  
}
