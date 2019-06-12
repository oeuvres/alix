package alix.lucene;

import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;

/**
 * Creates a formatted snippet from the top passages.
 * <p>
 * The default implementation marks the query terms as bold, and places
 * ellipses between unconnected passages.
 */
public class HiliteFormatter extends PassageFormatter {
  /** text that will appear before highlighted terms */
  protected final String preTag;
  /** text that will appear after highlighted terms */
  protected final String postTag;
  /** text that will appear between two unconnected passages */
  protected final String ellipsis;
  /** true if we should escape for html */
  protected final boolean detag;

  /**
   * Creates a new DefaultPassageFormatter with the default tags.
   */
  public HiliteFormatter() {
    this("<mark>", "</mark>", " <samp class=\"\">[…]</samp> ", true);
  }

  /**
   * Creates a new DefaultPassageFormatter with custom tags.
   *
   * @param preTag   text which should appear before a highlighted term.
   * @param postTag  text which should appear after a highlighted term.
   * @param ellipsis text which should be used to connect two unconnected passages.
   * @param detag    true if html tags should be stripped
   */
  public HiliteFormatter(String preTag, String postTag, String ellipsis, boolean detag) {
    if (preTag == null || postTag == null || ellipsis == null) {
      throw new NullPointerException();
    }
    this.preTag = preTag;
    this.postTag = postTag;
    this.ellipsis = ellipsis;
    this.detag = detag;
  }

  @Override
  public String format(Passage passages[], String content) {
    StringBuilder sb = new StringBuilder();
    int pos = 0;
    for (Passage passage : passages) {
      // don't add ellipsis if its the first one, or if its connected.
      if (passage.getStartOffset() > pos && pos > 0) {
        sb.append(ellipsis);
      }
      pos = passage.getStartOffset();
      for (int i = 0; i < passage.getNumMatches(); i++) {
        int start = passage.getMatchStarts()[i];
        assert start >= pos && start < passage.getEndOffset();
        //append content before this start
        append(sb, content, pos, start);

        int end = passage.getMatchEnds()[i];
        assert end > start;
        // its possible to have overlapping terms.
        //   Look ahead to expand 'end' past all overlapping:
        while (i + 1 < passage.getNumMatches() && passage.getMatchStarts()[i+1] < end) {
          end = passage.getMatchEnds()[++i];
        }
        end = Math.min(end, passage.getEndOffset()); // in case match straddles past passage

        sb.append(preTag);
        append(sb, content, start, end);
        sb.append(postTag);

        pos = end;
      }
      // its possible a "term" from the analyzer could span a sentence boundary.
      append(sb, content, pos, Math.max(pos, passage.getEndOffset()));
      pos = passage.getEndOffset();
    }
    return sb.toString();
  }

  /**
   * Appends original text to the response.
   *
   * @param dest    resulting text, possibly transformed or encoded
   * @param content original text content
   * @param start   index of the first character in content
   * @param end     index of the character following the last character in content
   */
  protected void append(final StringBuilder dest, final String content, final int start, final int end) {
    if (detag) {
      detag(dest, content, start, end);
    } else {
      dest.append(content, start, end);
    }
  }
  /**
   * Copy text from html(start-end) to dest, but without tags.
   * cases : "broken start tag>  <tag>  <broken end tag"
   * 
   * @param dest
   * @param html
   * @param start
   * @param end
   */
  public static void detag(StringBuilder dest, String html, int start, int end) {
    boolean lt = false, first = true;
    int lastLt = start; // index of last <, to erase broken ending tag
    for (int i = start; i < end; i++) {
      char c = html.charAt(i);
      switch (c) {
        case '<':
          first = false; // no broken tag at start
          lt = true;
          lastLt = i;
          break;
        case '>':
          lt = false;
          // a broken tag at start, erase what was appended
          if (first) {
            dest.setLength(dest.length()-(i-start));
            first = false;
            break;
          }
          break;
        default:
          if (lt) break;
          else dest.append(c);
      }
    }
    // if a tag is not yet closed, nothing has been recorded, we are OK
  }

}
