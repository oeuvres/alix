/*
 * © Pierre DITTGEN <pierre@dittgen.org> 
 * 
 * Alix : [A] [L]ucene [I]ndexer for [X]ML documents
 * 
 * Alix is a command-line tool intended to parse XML documents and to index
 * them into a Lucene Index
 * 
 * This software is governed by the CeCILL-C license under French law and
 * abiding by the rules of distribution of free software.  You can  use, 
 * modify and/ or redistribute the software under the terms of the CeCILL-C
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info". 
 * 
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability. 
 * 
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or 
 * data to be ensured and,  more generally, to use and operate it in the 
 * same conditions as regards security. 
 * 
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-C license and that you accept its terms.
 * 
 */
package alix.lucene;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.HashMap;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.w3c.dom.Node;

import net.sf.saxon.TransformerFactoryImpl;
import net.sf.saxon.s9api.ExtensionFunction;
import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.OccurrenceIndicator;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmValue;

/**
 * Alix entry-point
 * 
 * @author Pierre DITTGEN (2012, original idea, creation)
 * @author glorieux-f
 */
public class Alix
{
  /** Mandatory content, XML file name, maybe used for update */
  public static final String FILENAME = "FILENAME";
  /** Mandatory content, XML file name, maybe used for update */
  public static final String OFFSETS = "OFFSETS";
  /** For each field, a dictionary of the terms in frequency order */
  final static HashMap<String, BytesDic> bytesDics = new HashMap<String, BytesDic>();
  /** Current filename proceded */
  public static final FieldType ftypeText = new FieldType();
  static {
    // inverted index
    ftypeText.setTokenized(true);
    // position needed for phrase query
    ftypeText.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    // keep
    ftypeText.setStored(true);
    ftypeText.setStoreTermVectors(true);
    ftypeText.setStoreTermVectorOffsets(true);
    ftypeText.setStoreTermVectorPositions(true);
    // http://makble.com/what-is-lucene-norms, omit norms (length normalization)
    ftypeText.setOmitNorms(true);
    ftypeText.freeze();
  }
  /** Pool of instances, unique by path */
  public static final HashMap<Path, Alix> pool = new HashMap<Path, Alix>();
  /** Normalized path */
  public final Path path;
  /** The lucene directory, kept private, to avoid closing by a thread */
  private Directory dir;
  /** The IndexReader if requested */
  private IndexReader reader;
  /** The IndexWriter if requested */
  private IndexWriter writer;
  /** The IndexSearcher if requested */
  private IndexSearcher searcher;

  /**
   * Avoid constructions, maintain a pool by file path
   * 
   * @throws IOException
   */
  private Alix(final Path path) throws IOException
  {
    this.path = path;
    Files.createDirectories(path);
    // dir = FSDirectory.open(indexPath);
    dir = MMapDirectory.open(path); // https://dzone.com/articles/use-lucene’s-mmapdirectory
  }

  public static Alix instance(final String path) throws IOException
  {
    return instance(Paths.get(path));
  }

  public static Alix instance(Path path) throws IOException
  {
    path = path.toAbsolutePath();
    Alix alix = pool.get(path);
    if (alix == null) {
      alix = new Alix(path);
      pool.put(path, alix);
    }
    return alix;
  }

  public IndexReader reader() throws IOException
  {
    return reader(false);
  }

  public IndexReader reader(final boolean force) throws IOException
  {
    if (!force && reader != null) return reader;
    // near real time reader
    if (writer != null && writer.isOpen()) reader = DirectoryReader.open(writer, true, true);
    else reader = DirectoryReader.open(dir);
    return reader;
  }

  public IndexSearcher searcher() throws IOException
  {
    return searcher(false);
  }

  public IndexSearcher searcher(final boolean force) throws IOException
  {
    if (!force && searcher != null) return searcher;
    reader(force);
    searcher = new IndexSearcher(reader);
    return searcher;
  }

  /**
   * Get a lucene writer
   * 
   * @throws IOException
   */
  public IndexWriter writer() throws IOException
  {
    if (writer != null && writer.isOpen()) return writer;
    Analyzer analyzer = new AnalyzerAlix();
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    conf.setUseCompoundFile(false); // show separate file by segment
    // may needed, increase the max heap size to the JVM (eg add -Xmx512m or
    // -Xmx1g):
    conf.setRAMBufferSizeMB(48);
    conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
    conf.setSimilarity(new BM25Similarity());
    writer = new IndexWriter(dir, conf);
    return writer;
  }

  public BytesDic bytesDic(final String field) throws IOException
  {
    
    BytesDic bytesDic = bytesDics.get(field);
    if (bytesDic != null) return bytesDic;
    bytesDic = new BytesDic(field);
    // ensure reader
    IndexReader reader = reader();
    bytesDic.docs = reader.getDocCount(field);
    bytesDic.occs = reader.getSumTotalTermFreq(field);
    BytesRef bytes;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      TermsEnum tenum = leaf.terms(field).iterator();
      while((bytes=tenum.next()) != null) {
        bytesDic.add(bytes, tenum.totalTermFreq());
      }
    }
    bytesDic.sort();
    bytesDics.put(field, bytesDic);
    return bytesDic;
  }

  
  /**
   * Parses command-line
   */
  public static void main(String args[]) throws Exception
  {
    String usage = "java alix.lucene.Alix parser.xsl lucene-index corpus/*.xml\n\n"
        + "Parse the files in corpus, with xsl parser, to be indexed in lucene index directory";
    if (args.length < 3) {
      System.err.println("Usage: " + usage);
      System.exit(1);
    }

    Date start = new Date();
    /*
     * Alix.walk(args[0], args[1], args[2]); Date end = new Date();
     * info(end.getTime() - start.getTime() + " total ms.");
     */
  }

}
