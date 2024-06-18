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
        for (Field f : this.getClass().getFields()) {
            if (!f.isEnumConstant())
                continue;
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
     * Output options as html &lt;option&gt; in order of a space separated list of
     * tokens.
     * 
     * @param list Oredered options.
     * @return HTML.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public default String options(String list)
    {
        StringBuilder sb = new StringBuilder();
        // let cry if list is empty
        String[] values = list.split("[\\s,;]+");
        Class cls = ((Enum<?>) this).getDeclaringClass();
        for (String name : values) {
            Option opt = null;
            try {
                opt = (Option) Enum.valueOf(cls, name);
            } catch (IllegalArgumentException e) {
                sb.append("<!-- " + name + "  " + cls + "  " + e + "-->\n");
                continue;
            }
            html(sb, opt);
        }
        return sb.toString();
    }

    public default void html(StringBuilder sb, Option option)
    {
        sb.append("<option");
        if (this == option)
            sb.append(" selected=\"selected\"");
        sb.append(" value=\"").append(option.toString()).append("\"");
        if (hint() != null)
            sb.append(" title=\"").append(option.hint()).append("\"");
        sb.append(">");
        sb.append(option.label());
        sb.append("</option>\n");

    }

}
