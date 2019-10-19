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
import java.util.HashMap;
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

import alix.lucene.Alix;
import alix.lucene.analysis.CharsMaps;
import alix.lucene.analysis.tokenattributes.CharsAtt;


/**
 * Tools to display a document
 */
public class Doc
{
  /** Format numbers with the dot */
  final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
  /** The lucene index to read in */
  final private Alix alix;
  /** Id of a document in this reader {@link IndexReader#document(int)} */
  final int docId;
  /** The document with stored field */
  final private Document document;
  /** Cache of term vector  */
  private HashMap<String, Terms> vectors = new HashMap<>();
  
  public Doc(Alix alix, int docId) throws IOException 
  {
    this.alix = alix;
    this.docId = docId;
    this.document = alix.reader().document(docId);
  }
  
  public Document document()
  {
    return document;
  }
  
  class TokenOffsets implements Comparable<TokenOffsets>
  {
    final int pos;
    final int start;
    final int end;
    final String form;
    final double count1;
    final double count2;
    public TokenOffsets(final int pos, final int start, final int end)
    {
      this(pos, start, end, null, 0, 0);
    }
    public TokenOffsets(final int pos, final int start, final int end, final String form, final double count1, final double count2)
    {
      this.pos = pos;
      this.start = start;
      this.end = end;
      this.form = form;
      this.count1 = count1;
      this.count2 = count2;
    }
    @Override
    public int compareTo(TokenOffsets tok2)
    {
      return Integer.compare(this.start, tok2.start);
    }
  }
  /**
   * Return the count of tokens of this doc for field.
   * @param field
   * @return
   * @throws IOException
   */
  public int length(String field) throws IOException
  {
    return alix.docLength(field)[this.docId];
  }

  public String contrast(final String field, final int docId2) throws IOException, NoSuchFieldException
  {
    return contrast(field, docId2, false);
  }
  
  
  /**
   * 
   * @param field
   * @param docId2
   * @param right 
   * @return
   * @throws IOException
   * @throws NoSuchFieldException
   */
  public String contrast(final String field, final int docId2, final boolean right) throws IOException, NoSuchFieldException
  {
    StringBuilder sb = new StringBuilder();

    int[] docLength = alix.docLength(field);
    int length1 = docLength[docId];
    int length2 = docLength[docId2];
    Terms vek1 = getTermVector(field);
    Terms vek2 = alix.reader().getTermVector(docId2, field);
    TermsEnum termit1 = vek1.iterator();
    BytesRef term1;
    TermsEnum termit2 = vek2.iterator();
    BytesRef term2 = termit2.next();
    ArrayList<TokenOffsets> offsets = new ArrayList<TokenOffsets>();
    PostingsEnum postings = null;
    // loop on terms source, compare with dest
    double max1 = Double.MIN_VALUE;
    double max2 = Double.MIN_VALUE;
    CharsAtt att = new CharsAtt();
    while(termit1.next() != null) {
      // termit1.ord(); UnsupportedOperationException
      final double count1 = (int)termit1.totalTermFreq();
      term1 = termit1.term();
      String form = term1.utf8ToString();
      att.setEmpty().append(form);
      if (CharsMaps.isStop(att)) continue;

      double count2 = 0;
      while(true) {
        if (term2 == null) break;
        int comp = term1.compareTo(term2);
        if (comp < 0) break; // term2 is bigger, get it after
        if (comp == 0) { // match
          count2 = (int) termit2.totalTermFreq();
          break;
        }
        term2 = termit2.next();
      }
      if (max1 < count1) max1 = count1;
      if (max2 < count2) max2 = count2;
      // loop on positions to get offset
      postings = termit1.postings(postings, PostingsEnum.OFFSETS);
      while(postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
        int pos = -1;
        for (int freq = postings.freq(); freq > 0; freq --) {
          pos = postings.nextPosition();
          offsets.add(new TokenOffsets(pos, postings.startOffset(), postings.endOffset(), form, count1, count2));
        }
      }
    }
    String text = document.get(field);
    Collections.sort(offsets); // sort offsets before hilite
    int off = 0;
    final double scoremax = max1/length1 + max2/length2;
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      double count1 = tok.count1;
      double count2 = tok.count2;
      // skip token
      if (count2 == 0 && count1 < 2) continue;
      sb.append(text.substring(off, tok.start)); // append text before token
      String type = "tokshared";
      // specific to this doc
      if (count2 == 0) type = "tokspec"; 
      // change boldness
      double score = count1/length1 + count2/length2;
      double sum = count1 + count2;
      String level = "em1";
      if (score >= 0.6*scoremax) level = "em9";
      else if (score >= 0.3*scoremax) level = "em5";
      else if (sum > 4) level = "em3";
      else level = "em2";
      
      String form = tok.form.replace(' ', '_');
      String title = "";
      if(right) title += (int)tok.count2+" | "+ (int)tok.count1;
      else  title += (int)tok.count1+" | "+ (int)tok.count2;
      title += " occurremces";
      sb.append("<a id=\"tok"+tok.pos+"\" class=\""+type+" "+form+" "+level+"\" title=\""+title+"\">");
      sb.append(text.substring(tok.start, tok.end));
      sb.append("</a>");
      off = tok.end;
    }
    sb.append(text.substring(off)); // do not forget end
    return sb.toString();
  }
  
  public String hilite(String field, String[] terms) throws IOException, NoSuchFieldException
  {
    ArrayList<BytesRef> list = new ArrayList<>();
    for (String t: terms) {
      list.add(new BytesRef(t));
    }
    return hilite(field, list);
  }

  /**
   * @throws IOException 
   * @throws NoSuchFieldException 
   * 
   */
  public Terms getTermVector(String field) throws IOException, NoSuchFieldException
  {
    Terms tvek = vectors.get(field);
    if (tvek == null) tvek = alix.reader().getTermVector(docId, field);
    if (tvek == null) throw new NoSuchFieldException("Missig terms Vector for field="+field+" docId="+docId);
    vectors.put(field, tvek);
    return tvek;
  }
  /**
   * Hilite terms in a stored document as html.
   * @param field
   * @throws IOException 
   * @throws NoSuchFieldException 
   */
  public String hilite(String field, ArrayList<BytesRef> refList) throws IOException, NoSuchFieldException
  {
    StringBuilder sb = new StringBuilder();
    String text = document.get(field);
    // maybe to cache ?
    Terms tvek = getTermVector(field);
    // buid a term enumeration like lucene like them in the term vector
    Automaton automaton = DaciukMihovAutomatonBuilder.build(refList);
    TermsEnum tEnum = new CompiledAutomaton(automaton).getTermsEnum(tvek);
    ArrayList<TokenOffsets> offsets = new ArrayList<TokenOffsets>();
    PostingsEnum postings = null;
    while (tEnum.next() != null) {
      postings = tEnum.postings(postings, PostingsEnum.OFFSETS);
      while(postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
        int pos = -1;
        for (int freq = postings.freq(); freq > 0; freq --) {
          pos = postings.nextPosition();
          offsets.add(new TokenOffsets(pos, postings.startOffset(), postings.endOffset()));
        }
      }
    }
    Collections.sort(offsets); // sort offsets before hilite
    int offset = 0;
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      sb.append(text.substring(offset, tok.start));
      sb.append("<mark class=\"mark\" id=\"mark"+(i+1)+"\">");
      if (i > 0) sb.append("<a href=\"#mark"+(i)+"\" onclick=\"location.replace(this.href); return false;\" class=\"prev\">◀</a> ");
      sb.append(text.substring(tok.start, tok.end));
      if (i < size - 1) sb.append(" <a href=\"#mark"+(i + 2)+"\" onclick=\"location.replace(this.href); return false;\" class=\"next\">▶</a>");
      sb.append("</mark>");
      offset = tok.end;
    }
    sb.append(text.substring(offset));
    
    int length = text.length();
    sb.append("<nav id=\"ruloccs\">\n");
    final DecimalFormat dfdec1 = new DecimalFormat("0.#", ensyms);
    for (int i = 0, size = offsets.size(); i < size; i++) {
      TokenOffsets tok = offsets.get(i);
      offset = tok.start;
      sb.append("<a href=\"#mark"+(i+1)+"\" style=\"top: "+dfdec1.format(100.0 * offset / length)+"%\"> </a>\n");
    }
    sb.append("</nav>\n");
    return sb.toString();
  }
  
  /*
  String text = document.get(TEXT);
  BinaryUbytes tags = new BinaryUbytes();
  tags.open(document.getBinaryValue(TEXT+Alix._TAGS));
  Offsets offsets = new Offsets();
  offsets.open(document.getBinaryValue(TEXT+Alix._OFFSETS));
  
  
  TagFilter tagFilter = new TagFilter();
  tagFilter.setName();
  tagFilter.setAdj();
  tagFilter.set(Tag.ADV);
  
  // hilite
  int off = 0;
  for (int pos = 0, size = offsets.size(); pos < size; pos++) {
    int tag = tags.get(pos);
    if (!tagFilter.accept(tag)) continue;
    int offStart = offsets.getStart(pos);
    int offEnd = offsets.getEnd(pos);
    out.print(text.substring(off, offStart));
    out.print("<mark class=\""+Tag.label(Tag.group(tag))+"\">");
    out.print(text.substring(offStart, offEnd));
    out.print("</mark>");
    off = offEnd;
  }
  out.print(text.substring(off));
  */

}
