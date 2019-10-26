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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.DaciukMihovAutomatonBuilder;

import alix.lucene.Alix;
import alix.lucene.analysis.FrDics;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.search.Rail.Token;
import alix.lucene.util.BinaryInts;
import alix.util.Char;
import alix.util.Top;


/**
 * Tools to display a document
 */
public class Doc
{
  /** Just the mandatory fields */
  final static HashSet<String> FIELDS_REQUIRED = new HashSet<String>(Arrays.asList(new String[] {  Alix.FILENAME, Alix.BOOKID, Alix.ID, Alix.LEVEL}));
  /** Format numbers with the dot */
  final static DecimalFormatSymbols ensyms = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
  /** The lucene index to read in */
  final private Alix alix;
  /** Id of a document in this reader {@link IndexReader#document(int)} */
  final int docId;
  /** Permanent id for the document */
  final String id;
  /** Set of fields loaded */
  final private HashSet<String> fieldsToLoad;
  /** The loaded fields */
  final private Document document;
  /** Cache of term vector by fields */
  private HashMap<String, Terms> vectors = new HashMap<>();
  /** Cache of offsets rail */
  private HashMap<String, Rail> rails = new HashMap<>();
  /** Cache of different top terms */
  private HashMap<String, Top<String>> tops =  new HashMap<>();
  
  /**
   * Get a document by String id (persists as long as the source XML doc is not modified)
   * with all fields loaded (even the big ones).
   * @param alix
   * @param id
   * @throws IOException
   */
  public Doc(final Alix alix, final String id) throws IOException 
  {
    this(alix, id, null);
  }
  /**
   * Get a document by String id (persists as long as the source XML doc is not modified),
   * with the set of fields provided (or all fields fieldsToLoad is null).
   * @param alix
   * @param id
   * @param fields Set 
   * @throws IOException
   */
  public Doc(final Alix alix, final String id, final HashSet<String> fieldsToLoad) throws IOException 
  {
    TermQuery qid = new TermQuery(new Term(Alix.ID, id));
    TopDocs search = alix.searcher().search(qid, 1);
    ScoreDoc[] hits = search.scoreDocs;
    if (hits.length == 0) {
      throw new IllegalArgumentException("No document found with id: "+id);
    }
    if (hits.length > 1) {
      throw new IllegalArgumentException(""+hits.length + "document found for "+qid);
    }
    int docId = hits[0].doc;
    if (fieldsToLoad == null) {
      document = alix.reader().document(docId);
    } 
    else {
      fieldsToLoad.addAll(FIELDS_REQUIRED);
      document = alix.reader().document(docId, fieldsToLoad);
    }
    this.alix = alix;
    this.docId = docId;
    this.id = id;
    this.fieldsToLoad = fieldsToLoad;
  }
  
  /**
   * Get a document by lucene docId (persists as long as the Lucene index is not modified)
   * with all fields loaded (even the big ones).
   * @param alix
   * @param docId
   * @throws IOException 
   */
  public Doc(final Alix alix, final int docId) throws IOException
  {
    this(alix, docId, null);
  }
  
  /**
   * Get a document by lucene docId (persists as long as the Lucene index is not modified)
   * with the set of fields provided (or all fields fieldsToLoad is null).
   * @param alix
   * @param id
   * @param fields
   * @throws IOException
   */
  public Doc(final Alix alix, final int docId, final HashSet<String> fieldsToLoad) throws IOException 
  {
    if (fieldsToLoad == null) {
      document = alix.reader().document(docId);
    } 
    else {
      fieldsToLoad.addAll(FIELDS_REQUIRED);
      document = alix.reader().document(docId, fieldsToLoad);
    }
    if (document == null) {
      throw new IllegalArgumentException("No stored fields found for docId: "+docId);
    }
    this.alix = alix;
    this.docId = docId;
    this.id = document.get(Alix.ID);
    this.fieldsToLoad = fieldsToLoad;
  }

  /**
   * Returns the persistent String id of the document.
   * @return
   */
  public String id()
  {
    return id;
  }

  /**
   * Returns the local Lucene int docId of the document.
   * @return
   */
  public int docId()
  {
    return docId;
  }

  /**
   * Returns the loaded document.
   */
  public Document doc()
  {
    return document;
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

  
  /**
   * Get and cache a term vector for a field of this document.
   * 
   * @throws IOException 
   * @throws NoSuchFieldException 
   */
  public Terms getTermVector(String field) throws IOException, NoSuchFieldException
  {
    Terms tvek = vectors.get(field);
    if (tvek != null) return tvek; // cache OK
    tvek = alix.reader().getTermVector(docId, field);
    if (tvek == null) throw new NoSuchFieldException("Missig terms Vector for field="+field+" docId="+docId);
    vectors.put(field, tvek);
    return tvek;
  }

  /**
   * 
   * @param field
   * @return
   * @throws IOException 
   * @throws NoSuchFieldException 
   */
  public String paint(final String field) throws NoSuchFieldException, IOException
  {
    if (fieldsToLoad != null && !fieldsToLoad.contains(field)) {
      throw new NoSuchFieldException("The field \""+field+"\" has not been loaded with the document \""+id+"\"");
    }
    String text = document.get(field);
    if (text == null) {
      throw new NoSuchFieldException("No text for the field \""+field+"\" in the document \""+id+"\"");
    }
    Terms tvek = getTermVector(field);
    final Rail rail = new Rail(field, docId, tvek, FrDics.STOP_BYTES, null);
    final int countMax = rail.countMax;
    final Token[] toks = rail.toks;
    final StringBuilder sb = new StringBuilder();
    //loop on all token of text
    int off = 0;
    for (int i = 0, len = toks.length; i < len; i++) {
      final Token tok = toks[i];
      final int count = tok.count;
      
      final String form = tok.form;
      sb.append(text.substring(off, tok.start)); // append text before token
      // change boldness
      String level;
      if (count == 1) level = "em1";
      else if (count < 4) level = "em2";
      else if (count >= 0.6*countMax) level = "em9";
      else if (count >= 0.3*countMax) level = "em5";
      else level = "em3";
      
      String title = "";
      title += count+" occurremces";
      String type = "WORD";
      if (Char.isUpperCase(form.charAt(0))) type = "NAME";
      
      sb.append("<a id=\"tok"+tok.pos+"\" class=\""+form.replace(' ', '_')+" "+level+"\" title=\""+title+"\">");
      sb.append(text.substring(tok.start, tok.end));
      sb.append("</a>");
      off = tok.end;
    }
    sb.append(text.substring(off)); // do not forget end
    return sb.toString();
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
    if (fieldsToLoad != null && !fieldsToLoad.contains(field)) {
      throw new NoSuchFieldException("The field \""+field+"\" has not been loaded with the document \""+id+"\"");
    }
    String text = document.get(field);
    if (text == null) {
      throw new NoSuchFieldException("No text for the field \""+field+"\" in the document \""+id+"\"");
    }
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
    ArrayList<Token> offsets = new ArrayList<Token>();
    PostingsEnum postings = null;
    // loop on terms source, compare with dest
    double max1 = Double.MIN_VALUE;
    double max2 = Double.MIN_VALUE;
    CharsAtt att = new CharsAtt();
    while(termit1.next() != null) {
      // termit1.ord(); UnsupportedOperationException
      final int count1 = (int)termit1.totalTermFreq();
      term1 = termit1.term();
      String form = term1.utf8ToString();
      att.setEmpty().append(form);
      if (FrDics.isStop(att)) continue;

      int count2 = 0;
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
          offsets.add(new Token(pos, postings.startOffset(), postings.endOffset(), form, count1, count2));
        }
      }
    }
    Collections.sort(offsets); // sort offsets before hilite
    int off = 0;
    final double scoremax = max1/length1 + max2/length2;
    for (int i = 0, size = offsets.size(); i < size; i++) {
      Token tok = offsets.get(i);
      double count1 = tok.count;
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
      if(right) title += (int)tok.count2+" | "+ (int)tok.count;
      else  title += (int)tok.count+" | "+ (int)tok.count2;
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
   * Hilite terms in a stored document as html.
   * @param field
   * @throws IOException 
   * @throws NoSuchFieldException 
   */
  public String hilite(String field, ArrayList<BytesRef> refList) throws IOException, NoSuchFieldException
  {
    String text = document.get(field);
    if (fieldsToLoad != null && !fieldsToLoad.contains(field)) {
      throw new IllegalArgumentException("The field \""+field+"\" has not been loaded with the document \""+id+"\"");
    }
    if (text == null) {
      throw new IllegalArgumentException("No text for the field \""+field+"\" in the document \""+id+"\"");
    }
    StringBuilder sb = new StringBuilder();
    // maybe to cache ?
    Terms tvek = getTermVector(field);
    // buid a term enumeration like lucene in the term vector
    Automaton automaton = DaciukMihovAutomatonBuilder.build(refList);
    TermsEnum tEnum = new CompiledAutomaton(automaton).getTermsEnum(tvek);
    ArrayList<Token> offsets = new ArrayList<Token>();
    PostingsEnum postings = null;
    while (tEnum.next() != null) {
      postings = tEnum.postings(postings, PostingsEnum.OFFSETS);
      while(postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
        int pos = -1;
        for (int freq = postings.freq(); freq > 0; freq --) {
          pos = postings.nextPosition();
          offsets.add(new Token(pos, postings.startOffset(), postings.endOffset()));
        }
      }
    }
    Collections.sort(offsets); // sort offsets before hilite
    int offset = 0;
    for (int i = 0, size = offsets.size(); i < size; i++) {
      Token tok = offsets.get(i);
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
    sb.append("<nav id=\"ruloccs\"><div>\n");
    final DecimalFormat dfdec1 = new DecimalFormat("0.#", ensyms);
    for (int i = 0, size = offsets.size(); i < size; i++) {
      Token tok = offsets.get(i);
      offset = tok.start;
      sb.append("<a href=\"#mark"+(i+1)+"\" style=\"top: "+dfdec1.format(100.0 * offset / length)+"%\"> </a>\n");
    }
    sb.append("</div></nav>\n");
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
  //
  
  /**
   * Prepare list of terms 
   * @param field
   * @throws IOException
   * @throws NoSuchFieldException 
   */
  private void topWords(String field) throws IOException, NoSuchFieldException
  {
    int[] docLength = alix.docLength(field);
    Terms vector = getTermVector(field);
    int docLen = docLength[docId];
    // get index term stats
    Freqs freqs = alix.freqs(field);
    // loop on all terms of the document, get score, keep the top 
    TermsEnum termit = vector.iterator();
    final Top<String> names = new Top<String>(100);
    final Top<String> happax = new Top<String>(100);
    final Top<String> theme = new Top<String>(100);
    long occsAll= freqs.occsAll;
    int docsAll = freqs.docsAll;
    Scorer scorer = new ScorerBM25();
    // Scorer scorerTheme = new ScorerTheme();
    // Scorer scorerTfidf = new ScorerTfidf();
    scorer.setAll(occsAll, docsAll);
    CharsAtt att = new CharsAtt();
    while(termit.next() != null) {
      BytesRef bytes = termit.term();
      if (!freqs.contains(bytes)) continue; // should not arrive, set a pointer
      // count 
      int termDocs = freqs.docs(); // count of docs with this word
      long termOccs = freqs.length(); // count of occs accross base
      scorer.weight(termOccs, termDocs); // collection level stats
      int occsDoc = (int)termit.totalTermFreq(); // c
      double score = scorer.score(occsDoc, docLen);
      String term = bytes.utf8ToString();
      
      // keep all names, even uniques
      if (Char.isUpperCase(term.charAt(0))) {
        names.push(occsDoc, term);
      }
      else if (termDocs < 2) {
        happax.push(score, term);
      }
      else {
        att.setEmpty().append(term);
        if (!FrDics.isStop(att))
          theme.push(scorer.score(occsDoc, docLen), term);
      }
      
    }
    tops.put(field+"_theme", theme);
    tops.put(field+"_names", names);
    tops.put(field+"_happax", happax);
  }

  public Top<String> names(String field) throws IOException, NoSuchFieldException {
    Top<String> ret = tops.get(field+"_names");
    if (ret == null) topWords(field);
    return tops.get(field+"_names");
  }

  public Top<String> theme(String field) throws IOException, NoSuchFieldException {
    Top<String> ret = tops.get(field+"_theme");
    if (ret == null) topWords(field);
    return tops.get(field+"_theme");
  }

  public Top<String> happax(String field) throws IOException, NoSuchFieldException {
    Top<String> ret = tops.get(field+"_happax");
    if (ret == null) topWords(field);
    return tops.get(field+"_happax");
  }

  /**
   * Create the More like This query from a PriorityQueue
   */
  static public Query moreLikeThis(String field, Top<String> top, int words) {
    BooleanQuery.Builder query = new BooleanQuery.Builder();
    // double max = top.max();
    for (Top.Entry<String> entry: top) {
      // if (entry.score() <= 0) break;
      Query tq = new TermQuery(new Term(field, entry.value()));
      /*
      if (boost) {
        float factor = (float)(boostFactor * entry.score() / max);
        tq = new BoostQuery(tq, factor);
      }
      */
      query.add(tq, BooleanClause.Occur.SHOULD);
      if (--words < 0) break;
    }
    return query.build();
  }

}
