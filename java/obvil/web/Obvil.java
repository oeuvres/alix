package obvil.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * In an MVC model, this servlet is the global controller for Obvil app.
 * Model is the lucene index and alix java
 * is It is mainly an url dispatcher, accor
 */
public class Obvil extends HttpServlet
{
  /** forbidden name for corpus */
  static HashSet<String> STOP = new HashSet<String>();
  static {
    for (String s : new String[] {"WEB-INF", "static", "jsp", "reload"}) {
      STOP.add(s);
    }
  }
  /** Absolute folder of properties file and lucene index */
  private String obvilDir;
  /** List of available bases with properties */
  private HashMap<String, Properties> baseList = new HashMap<>();
  

  public void init(ServletConfig config) throws ServletException
  {
    super.init(config);
    props();
  }

  public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
  {
    if (request.getAttribute("obvil") != null) {
      throw new ServletException("[Obvil] {"+request.getRequestURI()+"} infinite loop error.");
    }
    String context = request.getContextPath(); 
    String url = request.getRequestURI().substring(context.length());
    Path path = Paths.get(url).normalize();
    request.setAttribute("obvil", true);
    request.setAttribute("obvilDir", obvilDir);
    request.setAttribute("path", path);
    request.setAttribute("url", url);
    request.setAttribute("context", context);
    if (path.getNameCount() == 0) {
      /* will bug behind proxies
      if (!url.equals("/")) {
        response.sendRedirect(context+"/");
        return;
      }
      */
      request.setAttribute("baseList", baseList);
      request.getRequestDispatcher("/jsp/bases.jsp").forward(request, response);
      return;
    }
    String base = path.getName(0).toString();
    // reload base list
    if ("reload".equals(base)) {
      props();
      throw new ServletException("[Obvil] reload base list.");
    }
    
    Properties props = baseList.get(base);
    if (props == null) {
      throw new ServletException("[Obvil] {"+base+ "} base not known on this server.");
    }
    request.setAttribute("base", base);
    request.setAttribute("props", props);
    // base welcome page
    if (path.getNameCount() == 1) {
      // ensure trailing space for relative links
      if (!url.equals("/"+base+"/")) {
        // response.sendRedirect(context+"/"+base+"/"); // will not work behind proxy
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        request.setAttribute("message", "Bad link, try <a href=\""+base+"/\">"+base+"</a> (with a slash).");
        request.setAttribute("redirect", base+"/");
        request.getRequestDispatcher("/jsp/error.jsp").forward(request, response);
        return;
      }
      request.getRequestDispatcher("/jsp/desk.jsp").forward(request, response);
      return;
    }
    // base component page
    // redirect 
    Path jsp = path.subpath(1, path.getNameCount());
    request.getRequestDispatcher("/jsp/"+jsp.toString()).forward(request, response);
  }

  /**
   * Loop on a folder containing configuration files.
   * 
   * @throws ServletException
   */
  private void props() throws ServletException
  {
    obvilDir = getServletContext().getRealPath("WEB-INF/obvil");
    // ensure trrailing slash (getRealPath() could fiffer between tomcat versions)
    if(!obvilDir.endsWith("/")) obvilDir += "/";
    File dir = new File(obvilDir);
    File[] ls = dir.listFiles();
    baseList.clear();
    for (File file : ls) {
      if (file.isDirectory()) continue;
      String filename = file.getName();
      int i = filename.lastIndexOf('.');
      if (i < 0) continue;
      String ext = filename.substring(i);
      if (!".xml".equals(ext)) continue;
      String code = filename.substring(0, i);
      if (STOP.contains(code)) {
        throw new ServletException("[Obvil conf] {"+code+ "} name forbdden for a base.");
      }
      if (!file.canRead()) {
        throw new ServletException("[Obvil conf] {"+filename + "} properties file impossible to read.");
      }
      Properties props = new Properties();
      try {
        props.loadFromXML(new FileInputStream(file));
      }
      catch (Exception e) {
        throw new ServletException("[Obvil conf] {"+filename + "} xml properties error.", e);
      }
      baseList.put(code, props);
    }
  }
}
