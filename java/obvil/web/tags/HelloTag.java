package obvil.web.tags;

import java.io.IOException;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.tagext.SimpleTagSupport;
/**
 * Test if tag lib is working.
 */
public class HelloTag extends SimpleTagSupport
{
  public void doTag() throws JspException, IOException {
    JspWriter out = getJspContext().getOut();
    out.println("La librairie de tags OBVIL fonctionne.");
  }
}
