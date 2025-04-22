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
package com.github.oeuvres.alix.web;

import java.lang.reflect.Field;

import javax.servlet.http.HttpServletRequest;

/**
 * Automates tabs link in a navigation bar.
 */
public interface Link
{

    /**
     * The link to page relative to root
     * 
     * @return &lt;a href="{href}?…"&gt;
     */
    public String href();

    /**
     * A text label for a link.
     * 
     * @return &lt;a&gt;{label}&lt;/a&gt;
     */
    public String label();

    /**
     * Text representing advisory information about the link.
     * 
     * @return &lt;a title="{hint}"&gt;
     */
    public String hint();

    /**
     * Http parameters to keep on links
     * 
     * @return &lt;a href="…?{par}=value&amp;{par}=value"&gt;
     */
    public String[] pars();

    /**
     * Display a tab as an html link &lt;a&gt;.
     * 
     * @param tab      A Link object.
     * @param sb       A String build to append to.
     * @param request  The http request to keep params.
     * @param hrefHome The base url.
     */
    public static void a(final Link tab, final StringBuilder sb, final HttpServletRequest request,
            final String hrefHome)
    {
        String here = request.getRequestURI();
        here = here.substring(here.lastIndexOf('/') + 1);

        sb.append("<a");
        sb.append(" href=\"").append(hrefHome).append(tab.href());
        boolean first = true;
        for (String par : tab.pars()) {
            String value = request.getParameter(par);
            if (value == null)
                continue;
            value = JspTools.escape(value);
            if (first) {
                first = false;
                sb.append("?");
            } else {
                sb.append("&amp;");
            }
            sb.append(par).append("=").append(value);
        }
        sb.append("\"");
        if (tab.hint() != null)
            sb.append(" title=\"").append(tab.hint()).append("\"");
        sb.append(" class=\"tab");
        if (tab.href().equals(here))
            sb.append(" selected");
        else if (here.equals("") && tab.href().startsWith("index"))
            sb.append(" selected");
        sb.append("\"");
        sb.append(">");
        sb.append(tab.label());
        sb.append("</a>");
    }

    /**
     * Loop on tabs to build a nav bar.
     * 
     * @param request to get parameters values {@link HttpServletRequest#getParameter(String)}.
     * @param hrefHome href prefix.
     * @return html for a nav bar.
     */
    public default String nav(final HttpServletRequest request, String hrefHome)
    {
        StringBuilder sb = new StringBuilder();
        if (hrefHome == null) {
            hrefHome = "";
        }
        for (Field f : this.getClass().getFields()) {
            if (!f.isEnumConstant()) {
                continue;
            }
            try {
                Link tab = (Link) f.get(null);
                if (tab.label() == null) { // tab no in bar
                    continue;
                }
                a(tab, sb, request, hrefHome);
            } catch (IllegalArgumentException | IllegalAccessException e) {
                continue;
            }
        }
        return sb.toString();
    }

}
