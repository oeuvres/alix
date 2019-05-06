package alix.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

public class AnalyzerAlix extends Analyzer
{

  @Override
  protected TokenStreamComponents createComponents(String fieldName)
  {
    Tokenizer src = new TokenizerFr();
    TokenStream result = new TokenLem(src);
    result = new TokenCloud(result);
    return new TokenStreamComponents(src, result);
  }

}
