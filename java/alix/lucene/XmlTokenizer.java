package alix.lucene;

import org.apache.lucene.analysis.util.CharTokenizer;

/**
 * A silly XML (or XHTML) tokenizer, jumping the tags but keeping the text position
 * 
 * @author glorieux-f
 *
 */
public class XmlTokenizer extends CharTokenizer {
  boolean tag;

  /**
   * The simple parser
   */
  protected boolean isTokenChar(int c) {
    if (this.tag && '>'==c) {
      this.tag = false;
      return false;
    }
    if ('<'==c) {
      this.tag = true;
      return false;
    }
    if (this.tag) {
      return false;
    }
    return Character.isLetter(c);
  }

}
