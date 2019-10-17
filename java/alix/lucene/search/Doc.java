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
package alix.lucene.search;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.DaciukMihovAutomatonBuilder;


/**
 * Tools to display a document
 */
public class Doc
{
  /** Format numbers with the dot */
  final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
  /** The lucene index to read in */
  final private IndexReader reader;
  /** Id of a document in this reader {@link IndexReader#document(int)} */
  final int docId;
  /** The document with stored field */
  final private Document document;
  
  public Doc(IndexReader reader, int docId) throws IOException 
  {
    this.reader = reader;
    this.docId = docId;
    this.document = reader.document(docId);
  }
  
  public Document document()
  {
    return document;
  }
  
  class TokenOffsets
  {
    final int startOffset;
    final int endOffset;
    final String className;
    public TokenOffsets(final int startOffset, final int endOffset, final String className)
    {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.className = className;
    }
  }
  
  public String hilite(String field, String[] terms) throws IOException
  {
    ArrayList<BytesRef> list = new ArrayList<>();
    for (String t: terms) {
      list.add(new BytesRef(t));
    }
    return hilite(field, list);
  }

  /**
   * Hilite terms in a stored document as html.
   * @param field
   * @throws IOException 
   */
  public String hilite(String field, ArrayList<BytesRef> refList) throws IOException
  {
    StringBuilder sb = new StringBuilder();
    String text = document.get(field);
    // maybe to cache ?
    Terms tVek = reader.getTermVector(docId, field);
    // buid a term enumeration like lucene like them in the term vector
    Automaton automaton = DaciukMihovAutomatonBuilder.build(refList);
    TermsEnum tEnum = new CompiledAutomaton(automaton).getTermsEnum(tVek);
    PostingsEnum postings = null;
    ArrayList<TokenOffsets> offsets = new ArrayList<TokenOffsets>();
    while (tEnum.next() != null) {
      postings = tEnum.postings(postings, PostingsEnum.OFFSETS);
      while(postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
        int pos = -1;
        for (int freq = postings.freq(); freq > 0; freq --) {
          postings.nextPosition();
          offsets.add(new TokenOffsets(postings.startOffset(), postings.endOffset(), null));
        }
      }
    }
    Collections.sort(offsets, new Comparator<TokenOffsets>()
    {
      @Override
      public int compare(TokenOffsets entry1, TokenOffsets entry2)
      {
        int v1 = entry1.startOffset;
        int v2 = entry2.startOffset;
        if (v1 < v2) return -1;
        if (v1 > v2) return +1;
        return 0;
      }
    });
    int offset = 0;
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      sb.append(text.substring(offset, tok.startOffset));
      sb.append("\n<mark class=\"mark\" id=\"mark"+(i+1)+"\">");
      if (i > 0) sb.append("<a href=\"#mark"+(i)+"\" onclick=\"location.replace(this.href); return false;\" class=\"prev\">◀</a> ");
      sb.append(text.substring(tok.startOffset, tok.endOffset));
      if (i < size - 1) sb.append(" <a href=\"#mark"+(i + 2)+"\" onclick=\"location.replace(this.href); return false;\" class=\"next\">▶</a>");
      sb.append("</mark>\n");
      offset = tok.endOffset;
    }
    sb.append(text.substring(offset));
    
    int length = text.length();
    sb.append("<nav id=\"ruloccs\">\n");
    final DecimalFormat dfdec1 = new DecimalFormat("0.#", ensyms);
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      offset = tok.startOffset;
      sb.append("<a href=\"#mark"+(i+1)+"\" style=\"top: "+dfdec1.format(100.0 * offset / length)+"%\"> </a>\n");
    }
    sb.append("</nav>\n");
    return sb.toString();
  }
  
}
