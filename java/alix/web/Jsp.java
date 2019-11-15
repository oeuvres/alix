/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.web;

import java.util.HashMap;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.PageContext;

/**
 * Jsp toolbox.
 */
public class Jsp
{
  /** Original request */
  final HttpServletRequest request;
  /** Original response */
  final HttpServletResponse response;
  /** Jsp page context */
  final PageContext page;
  /** Cookies */
  HashMap<String, String> cookies;
  /** for cookies */
  private final static int MONTH = 60 * 60 * 24 *30;

  /** Wrap the global jsp variables */
  public Jsp(final HttpServletRequest request, final HttpServletResponse response, PageContext page)
  {
    this.request = request;
    this.response = response;
    this.page = page;
  }

  /**
   * Get a cookie value by name.
   * @param name
   * @return null if not set
   */
  public String getCookie(final String name)
  {
    if (cookies == null) {
      Cookie[] cooks = request.getCookies();
      if (cooks == null) return null;
      cookies = new HashMap<String, String>();
      for (int i=0; i<cooks.length; i++) {
        Cookie cook = cooks[i];
        cookies.put(cook.getName(), cook.getValue());
      }
    }
    return cookies.get(name);
  }
  
  /** Check if a String is significant */
  public static boolean check(String s)
  {
    if (s == null) return false;
    s = s.trim();
    if (s.length() == 0) return false;
    if ("null".equals(s)) return false;
    return true;
  }

  /**
   * Ensure that a String could be included in an html attribute with quotes
   */
  public static String escape(final String s)
  {
    if (s == null) return "";
    final StringBuilder out = new StringBuilder(Math.max(16, s.length()));
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') out.append("&quot;");
      else if (c == '<') out.append("&lt;");
      else if (c == '>') out.append("&gt;");
      else if (c == '&') out.append("&amp;");
      else out.append(c);
    }
    return out.toString();
  }


  public void setCookie(String name, String value) 
  {
    if (name == null) return;
    Cookie cookie = new Cookie(name, value);
    cookie.setMaxAge(MONTH);
    response.addCookie(cookie);
  }
  public void resetCookie(String name) 
  {
    if (name == null) return;
    Cookie cookie = new Cookie(name, "");
    cookie.setMaxAge(-MONTH); // set in the past should reset
    response.addCookie(cookie);
  }

  /**
   * Get a request parameter as an int with a default value.
   */
  public int getInt(final String name, final int fallback)
  {
    return getInt(name, fallback, null);
  }

  
  public int getInt(final String name, final int fallback, final String cookie)
  {
    String value = request.getParameter(name);
    int ret = fallback;
    // a string submitted ?
    if (check(value)) {
      try {
        ret = Integer.parseInt(value);
        setCookie(cookie, ""+ret); // value seems ok, try to store it as cookie
      }
      catch (NumberFormatException e) {
        ret = fallback;
      }
    }
    if (cookie == null) return ret; // if no cookie key, nothing more todo
    // param has an empty value, seems that client wants to reset cookie
    // do not give back the stored value
    if (value != null && !check(value)) {
      resetCookie(name);
      return ret;
    }
    value = getCookie(cookie);
    if (value == null) return ret;
    // verify stored value before send it
    try {
      ret = Integer.parseInt(value);
    }
    catch (NumberFormatException e) {
      ret = fallback;
      // bad cookie value, reset it
      resetCookie(name);
    }
    return ret;
  }

  /**
   * Get a request parameter as a float with default value.
   */
  public float getFloat(final String name, final float fallback)
  {
    return getFloat(name, fallback, null);
  }

  public float getFloat(final String name, final float fallback, final String cookie)
  {
    String value = request.getParameter(name);
    float ret = fallback;
    if (check(value)) {
      try {
        ret = Float.parseFloat(value);;
        setCookie(cookie, ""+ret); // value seems ok, store it as a cookie
      }
      catch (NumberFormatException e) {
        ret = fallback;
      }
    }
    if (cookie == null) return ret; // if no cookie key, nothing more todo
    if (value != null && !check(value)) {
      resetCookie(name);
      return ret;
    }
    value = getCookie(cookie);
    if (value == null) return ret;
    // verify stored value before send it
    try {
      ret = Integer.parseInt(value);
    }
    catch (NumberFormatException e) {
      // bad cookie value, reset it
      resetCookie(name);
      ret = fallback;
    }
    return ret;
  }

  /**
   * Get a requesparameter as a String with a defaul value, or optional
   * persistency.
   */
  public String getString(final String name, final String fallback)
  {
    return getString(name, fallback, null);
  }

  public String getString(final String name, final String fallback, String cookie)
  {
    String value = request.getParameter(name);
    if (check(value)) {
      setCookie(cookie, value);
      return value;
    }
    if (cookie == null) return fallback;
    // try to deal with cookie
    value = getCookie(cookie);
    if (check(value)) return value;
    // cookie seenms to have a problem, reset it
    resetCookie(cookie);
    return fallback;
  }

  /**
   * Get a requesparameter as a String with a defaul value, or optional
   * persistency.
   */
  public boolean getBoolean(final String name, final boolean fallback)
  {
    return getBoolean(name, fallback, null);
  }

  public boolean getBoolean(final String name, final boolean fallback, String key)
  {
    String value = page.getRequest().getParameter(name);
    /*
    if (check(value)) {
      if (key != null) page.getSession().setAttribute(key, value);
      return value;
    }
    if (key != null) {
      value = (String) page.getSession().getAttribute(key);
      if (value != null) return value;
    }
    return fallback;
    */
    if (value == null) return false;
    else return true;
  }

}
