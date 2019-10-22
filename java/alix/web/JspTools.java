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

import javax.servlet.jsp.PageContext;

/**
 * Jsp toolbox.
 */
public class JspTools
{
  /** Jsp page context */
  final PageContext page;
  public JspTools(PageContext page)
  {
    this.page = page;
  }

  /** Check if a String is dignificant */
  public static boolean check(String s) {
    if (s == null) return false;
    s = s.trim();
    if (s.length() == 0) return false;
    if ("null".equals(s)) return false;
    return true;
  }

  /**
   * Ensure that a String could be included as an html attribute with quotes
   */
  public static String escapeHtml(String s) {
    StringBuilder out = new StringBuilder(Math.max(16, s.length()));
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

  /**
   * Get a request parameter as an int with default value, or optional session persistency.
   */
   public int get(final String name, final int fallback) {
     return get(name, fallback, null);
   }

   public int get(final String name, final int fallback, final String key) {
     String value = page.getRequest().getParameter(name);
     int ret;
     // a string submitted ?
     if (check(value)) {
       try {
         ret = Integer.parseInt(value);
       }
       catch(NumberFormatException e) {
         return fallback;
       }
       if (key != null) page.getSession().setAttribute(key, ret);
       return ret;
     }
     if (key != null) {
       Integer o = (Integer)page.getSession().getAttribute(key);
       if (o != null) return o;
     }
     return fallback;
   }

   /**
    * Get a requesparameter as a String with a defaul value, or optional persistency.
    */
   public String get(final String name, final String fallback) {
     return get(name, fallback, null);
   }
   public String get(final String name, final String fallback, String key) {
     String value = page.getRequest().getParameter(name);
     if (check(value)) {
       if (key != null) page.getSession().setAttribute(key, value);
       return value;
     }
     if (key != null) {
       value = (String)page.getSession().getAttribute(key);
       if (value != null) return value;
     }
     return fallback;
   }

}
