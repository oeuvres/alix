package alix.lucene.search;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;

import alix.lucene.Alix;

/**
 * <p>
 * Collect cooccurrences data from texts, according to 
 * Stefan Evert. 2005, 
 * <a href="https://elib.uni-stuttgart.de/bitstream/11682/2573/1/Evert2005phd.pdf">The Statistics of Word Cooccurrences, Word Pairs and Collocations.</a> 
 * and Gambette & Véronis 2009 
 * <a href="http://igm.univ-mlv.fr/~gambette/2009GambetteVeronis.pdf">Visualising a Text with a Tree Cloud</a>,
 * allowing to draw dendrograms or networks.
 * </p>
 * <p>
 * The raw frequency of words in texts give usually a lot of information about the contents.
 * For example, texts with lots of occurrences of the word "literature" should talk about "theater" and "poetry", 
 * but sometimes, the corpus is more about "scientific literature". The link between words is important.
 * Give a sensible picture of such information need some art, but first, we need to collect information,
 * and not forget what we could need.
 * </p>
 * <p>
 * If a sliding window of words (ex: 30, the span of a reader immediate attention) cross a set of texts of wc words (wc = word count) 
 * by some step (1 word is complete but expensive), it produces N events (= wc/step).
 * A data structure can record co-presence counts of words inside the window at each step.
 * Let say a="parce", and b="que" ("parce que" is a very common French phrase).
 * When "parce" is present in the window, the event could be counted by increment a variable called a1, when it is absent, call it a0; same for "que", b1 and b0.
 * Sometimes, a and b are together present in the window (ab11), 
 * a and b may also not be in the window (ab00), b="que" is very often in the window without a="parce" (ab01), and in very special case, when "parce"
 * is the last word of the window, a may be in the window without b="que" (ab10). 
 * The task could be done for a limited word list, or may be all, recording spare matrix of all couples.
 * We fill that a="parce" is highly connected to b="que", but "que" has 
 * lots of other connections (ex: "Est-ce que ?"). 
 * We would like nice formula to prune very common words with low signification, like "to be", 
 * but keeping them in non predictable cases, for example, the personal pronouns are not equally used and could be highly significant in some
 * contexts, like the second person "you" in a novel, usually in dialogs.
 * Other sciences has already encountered such problems, especially, natural history (biodiversity indices, species proximity by characteristics or genes).
 * </p>
 * <p>
 * Compiling statistic literature about this topic, Evert presents a lots of possible metric calculation using ab11, ab10, ab01, ab00,
 * but without a critic examination to choose one or another. Gambette & Veronis has evaluated 14 formula, and found that some are 
 * not enough robust (ex: delete one word out 5 does not affect too much sense for a human, it should not affect a calculated picture).
 * People who have played with such visualization know how volatile can be trees, classifications or pictures, with little modifications of parameters.
 * Good metrics should be robust.
 * The Gambette & Veronis results are very instructive, but they do not try an explanation of their observations. 
 * </p>
 * <ul>
 *  <li>Jaccard: a ∩ b / a ∪ b = ab11 / (ab11 + ab10 + ab01) </li>
 *  <li>Odds Ratio: log(
 *  <li>Mutual information: log(P(a, b)/P(a)P(b)) = (ab11 / N) / (a1/N * b1/N) = log(N * ab11 / a1*b1)</li>
 * </ul>
 * <p>
 * According to Gambette & Veronis, mutual information is the worst distance measure for their protocol, and Jaccard among the best.
 * The expected value (probability P(x)) is very common in statistics, but does it make sense for texts?
 * Expectation is said of random variables, like a dice, where the values from 1 to 6 define a closed and complete universe.
 * For texts, absence of a word does not make sense, because a corpus is never complete, there is always a universe outside of texts, 
 * with lost opuses and works to be written.
 * A corpus, even a supposed perfect book like Bible or Coran,
 * can always be another (different versions), so lexical statistics should not rely on the total of words N (expected value or negation).
 * A well known example will help. Consider the dictionary like a supermarket with lots of products. We can compare the basket of 2 people (2 texts)
 * according to what they have not buy (no children toys for each, does it mean that they have no children?). 
 * It is much more interesting to compare the products they have in common, and the ones they do not
 * share. It is exactly the goal of Jaccard formula a ∩ b / a ∪ b. Jaccard was a Swiss botanist comparing ecosystems, he knew that 
 * we can’t be confident in the absence of a species, all conclusions should rely on presences.
 * </p>
 * 
 * 
 */
public class WordLinks
{

  /**
   * To build a word distance matrix we need
   * <ul>
   *  <li>{@link Alix} alix: an index (lucene)</li>
   *  <li>String field: a field with indexed terms</li>
   *  <li>BitSet filter: a set of documents</li>
   *  <li>int dim: The number of terms to explore as a matrix, usually, the most frequents</li>
   * </ul>
   * Steps of construction
   * <ol>
   *  <li>Get the freqlist of terms for the set of documents, with a global int index for each term</li>
   *  <li>
   *  <li>A map from global term index to the local index (< nu
   * </ol>
   * @throws IOException 

   */
  public WordLinks(Alix alix, String field, BitSet docs, int dim) throws IOException {
    int winWidth = 30;
    Freqs freqs = alix.freqs(field);
    int[] global2local = new int[freqs.size];
    Arrays.fill(global2local, -1);
    TopTerms dic = freqs.topTerms(docs);
    dic.sortByOccs();
    int i = 0;
    while (dic.hasNext()) {
      dic.next();
      int termId = dic.termId();
      global2local[termId] = i;
      i++;
      if(i >= dim) break;
    }
    // loop on docsId in BitSet
    IndexReader reader = alix.reader();
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      BinaryDocValues binDocs = leaf.getBinaryDocValues(field);
      System.out.println(binDocs);
      // get a rail
      // for int (int i = 0, lim = rail.size + winWidth
    }

  }
  

}
