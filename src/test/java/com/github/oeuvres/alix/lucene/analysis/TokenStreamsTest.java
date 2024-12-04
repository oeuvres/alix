package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

public class TokenStreamsTest {

    
    @Test
    public void locution() throws IOException
    {
        String text = "";
        // File file = new File("src/test/resources/article.xml");
        // String text = Files.readString(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);
        text = "<h1 class=\"head\">Avant-propos\n"
            + "<br class=\"lb\"/>de la seconde édition<a class=\"bookmark\" href=\"#body\"> </a>\n"
            + "         </h1>\n"
            + "         <p class=\"noindent p\">C’est souvent à la fois un plaisir et une désillusion"
        ;
        text = "Le chemin de Fer d’intérêt local dont j’ai pris conscience dernièrement.";
        Analyzer ana = new AnalyzerAlix();
        analyze(ana.tokenStream("_cloud", text), text);
        ana.close();
    }

    public void tokML() throws IOException
    {
        String text = "\"pris conscience\" enfant";
        // String text = "Voir PIAGET, p. 10. À 10,5% en 1985, 1987 et 1988... U.S.S.R. ";
        // String text = "note<a class=\"noteref\" href=\"#note2\" id=\"note2_\" epub:type=\"noteref\">1</a>";
        // String text = "<span class=\"sc\">Piaget</span> (1907) <i>Un, moineau albinos</i>";
        // soft hyphen
        // String text = "Ce problème est de savoir si l’interprétation psycho-réflexo­logique exclut l’interprétation psychologique ou si elle la complète simplement.";
        TokenizerML tokenizer = new TokenizerML();
        tokenizer.setReader(new StringReader(text));
        analyze(tokenizer, text);
    }
    
    public void query() throws IOException
    {
        String text = "\"Pris conscience\" enfant aut*";
        Analyzer ana = new AnalyzerAlix();
        analyze(ana.tokenStream("_cloud", text), text);
        ana.close();
    }
    
    public void html() throws IOException
    {
        String text = "<p class=\"p\"><b>Lexical tokenization</b> is conversion of a text into (semantically or syntactically) meaningful <i>lexical tokens</i> belonging to categories defined by a \"lexer\" program. In case of a <a href=\"/wiki/Natural_language\" title=\"Natural language\">natural language</a>, those categories include nouns, verbs, adjectives, punctuations etc.";
        
        TokenizerML tokenizer = new TokenizerML();
        tokenizer.setReader(new StringReader(text));
        analyze(tokenizer, text);
    }
    
    private void analyze(TokenStream tokenStream, String text) throws IOException
    {
        

        final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAttribute = tokenStream.addAttribute(FlagsAttribute.class);
        final OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
        // final TypeAttribute typeAttribute = tokenStream.addAttribute(TypeAttribute.class);
        final PositionIncrementAttribute posIncAttribute = tokenStream.addAttribute(PositionIncrementAttribute.class);
        // final PositionLengthAttribute posLenAttribute = tokenStream.addAttribute(PositionLengthAttribute.class);
        final CharsAttImpl orthAttribute = (CharsAttImpl) tokenStream.addAttribute(OrthAtt.class);
        final CharsAttImpl lemAttribute = (CharsAttImpl) tokenStream.addAttribute(LemAtt.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            System.out.print(""
              + termAttribute.toString() + "\t" 
              + Tag.name(flagsAttribute.getFlags()) + "\t" 
              // + orthAtt.toString() + "|\t|" 
              + "|" + text.substring(offsetAttribute.startOffset(),  offsetAttribute.endOffset()) + "|\t" 
              // + offsetAttribute.startOffset() + "\t"
              // + offsetAttribute.endOffset() + "\t" 
              // + posIncAttribute.getPositionIncrement() + "\t"
              + "orth:" + orthAttribute.toString() + "\t" 
              + "lem:" + lemAttribute.toString() + "\t" 
              + "\n"
            );
        }
    }
    
    public void aposHyph() throws IOException
    {
        Analyzer ana = new AnalyzerCloud();
        String text = " 5.  évidence et la certitude sont toujours fonction l’une de l’autre. Connais-toi toi-même ?L’aujourd’hui d’hier. Parlons-en. Qu’est-ce que c’est ?";
        analyze(ana.tokenStream("field", text), text);
        ana.close();
    }

    static public class AposHyphenAnalyzer extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML(); // segment words
            TokenStream ts = new FilterAposHyphenFr(tokenizer);
            // ts = new FilterLemmatize(ts); // provide lemma+pos
            // ts = new FilterFind(ts); // orthographic form and lemma as term to index
            // ts = new ASCIIFoldingFilter(ts); // no accents
            return new TokenStreamComponents(tokenizer, ts);
        }

    }
    
    static public class AsciiAnalyzer extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML(); // segment words
            TokenStream result = new ASCIIFoldingFilter(tokenizer); // provide lemma+pos
            return new TokenStreamComponents(tokenizer, result);
        }

    }

    static public class AnalyzerLocution extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML(); // segment words
            TokenStream ts;
            ts = new FilterAposHyphenFr(tokenizer);
            ts = new FilterLemmatize(ts); // provide lemma+pos
            ts = new FilterLocution(ts); // concat known locutions
            return new TokenStreamComponents(tokenizer, ts);
        }

    }

    
    static public class AnalyzerLem extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML(); // segment words
            TokenStream result = new FilterLemmatize(tokenizer); // provide lemma+pos
            return new TokenStreamComponents(tokenizer, result);
        }

    }

}
