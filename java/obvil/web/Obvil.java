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
 * Model is the lucene index and alix java, View is the jsp pages.
 * It is mainly an url dispatcher.
 */
public class Obvil extends HttpServlet
{
  /** Request attribute name: internal messages for the servlet */
  public static final String OBVIL = "obvil";
  /** Request attribute name: the directory containing bases */
  public static final String OBVIL_DIR = "obvilDir";
  /** Request attribute name: set of bases, with their properties */
  public static final String BASE_LIST = "baseList";
  /** Request attribute name: the base name */
  public static final String BASE = "base";
  /** Request attribute name: Properties for the base */
  public static final String PROPS = "props";
  /** Request attribute name: error message for an error page */
  public static final String MESSAGE = "message";
  /** Request attribute name: URL redirection for client */
  public static final String REDIRECT = "redirect";
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
    request.setCharacterEncoding("UTF-8");
    if (request.getAttribute(OBVIL) != null) {
      throw new ServletException("[Obvil] {"+request.getRequestURI()+"} infinite loop error.");
    }
    String context = request.getContextPath(); 
    String url = request.getRequestURI().substring(context.length());
    Path path = Paths.get(url).normalize();
    request.setAttribute(OBVIL, true);
    request.setAttribute(OBVIL_DIR, obvilDir);
    if (path.getNameCount() == 0) {
      /* will bug behind proxies
      if (!url.equals("/")) {
        response.sendRedirect(context+"/");
        return;
      }
      */
      request.setAttribute(BASE_LIST, baseList);
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
    request.setAttribute(BASE, base);
    request.setAttribute(PROPS, props);
    // base welcome page
    if (path.getNameCount() == 1) {
      // ensure trailing space for relative links
      if (!url.equals("/"+base+"/")) {
        // response.sendRedirect(context+"/"+base+"/"); // will not work behind proxy
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        request.setAttribute(MESSAGE, "Bad link, try <a href=\""+base+"/\">"+base+"</a> (with a slash).");
        request.setAttribute(REDIRECT, base+"/");
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
