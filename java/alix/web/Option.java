package alix.web;

import java.lang.reflect.Field;

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
  
  public default String options()
  {
    StringBuilder sb = new StringBuilder();
    for (Field f: this.getClass().getFields()) {
      if (!f.isEnumConstant()) continue;
      try {
        Option option = (Option) f.get(null);
        String value = option.toString();
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
