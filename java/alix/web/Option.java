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

  public default String options() {
    StringBuilder sb = new StringBuilder();
    for (Field f: this.getClass().getFields()) {
      if (!f.isEnumConstant()) continue;
      try {
        Option option = (Option) f.get(null);
        html(sb, option);
      } catch (IllegalArgumentException | IllegalAccessException e) {
        continue;
      }
    }
    return sb.toString();
  }

  /**
   * Output options as html &lt;option> in order of 
   * a space separated list of tokens.
   * @param list
   * @return
   * @throws SecurityException 
   * @throws NoSuchFieldException 
   * @throws IllegalAccessException 
   * @throws IllegalArgumentException 
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public default String options(String list)
  {
    StringBuilder sb = new StringBuilder();
    // let cry if list is empty
    String[] values = list.split("\\s+");
    Class cls = this.getClass();
    for (String name: values) {
      Option opt = null;
      try {
        opt = (Option) Enum.valueOf(cls, name);
      } catch (IllegalArgumentException e) {
        continue;
      }
      html(sb, opt);
    }
    return sb.toString();
  }
  
  default void html(StringBuilder sb, Option option)
  {
    sb.append("<option");
    if (this == option) sb.append(" selected=\"selected\"");
    sb.append(" value=\"").append(option.toString()).append("\"");
    if (hint() != null) sb.append(" title=\"").append(option.hint()).append("\"");
    sb.append(">");
    sb.append(option.label());
    sb.append("</option>\n");
    
  }
  
}
