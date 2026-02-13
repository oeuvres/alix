package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import com.github.oeuvres.alix.common.Upos;
import com.github.oeuvres.alix.fr.FrDics;
import com.github.oeuvres.alix.fr.TagFr;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;

public class TokenStreamsTest {

    
    @Test
    static public void locution() throws IOException
    {
        // File dic = new File("../../ddr_lab/install/ddr-dic.csv");
        File dic = new File("../../piaget_labo/install/piaget-dic.csv");
        FrDics.load(dic.getCanonicalPath(), dic);
        dic = new File("../../piaget_labo/install/piaget-authors.csv");
        FrDics.load(dic.getCanonicalPath(), dic);

        String text = "";
        // File file = new File("src/test/resources/article.xml");
        // String text = Files.readString(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);
        text = "Il fallait naître jusqu’alors !";
        text = "D’accord, le chemin de Fer d’intérêt local dont j’ai pris conscience à cause d’enfants, parce qu’alors <aside>en note</aside> j’en veux !";
        text = "Parfois — rarement —, je parviens à me souvenir de certaines sensations profondes et indéfinies (telle sensation physique de bonheur, dans une rue au coucher du soleil, des phares d’automobiles étoilent le brouillard, les visages se cachent dans des fourrures, personne ne sait la richesse de ta vie…).";
        text = "<h1 class=\"head\">Avant-propos\n"
            + "<br class=\"lb\"/>de la seconde édition<a class=\"bookmark\" href=\"#body\"> </a>\n"
            + "         </h1>\n"
            + "         <p class=\"noindent p\">C’est souvent à la fois un plaisir et une désillusion.</p>"
        ;
        text = "le cercle extérieur de diamètre <hi>B.</hi> (A’ étant alors la largeur de l’intervalle entre eux deux) selon un certain nombre de rapports <hi>A’ — A : n,</hi> jusqu’à <hi>A’ = A,</hi> puis selon un certain nombre de rapports <hi>A’ = nA.</hi>";



        Analyzer ana = new AnalyzerAlix();
        // Analyzer ana = new AnalyzerLocution();
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
    
    private static void analyze(TokenStream tokenStream, String text) throws IOException
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
        int startLast = 0;
        while(tokenStream.incrementToken()) {
            final int startOffset = offsetAttribute.startOffset();
            final int flags = flagsAttribute.getFlags();
            String tag = TagFr.name(flags);
            if (tag == null) tag = Upos.name(flags);
            if (tag == null) tag = "" + flags;
            System.out.print(""
              + "term=" + termAttribute.toString() + "\t" 
              + tag + "\t" 
              // + orthAtt.toString() + "|\t|" 
              + "|" + text.substring(startOffset,  offsetAttribute.endOffset()) + "|\t" 
              + startOffset + "\t"
              + offsetAttribute.endOffset() + "\t" 
              // + posIncAttribute.getPositionIncrement() + "\t"
              + "orth=" + orthAttribute.toString() + "\t" 
              + "lem=" + lemAttribute.toString() + "\t" 
              + "\n"
            );
            if (startOffset < startLast) {
                System.out.println("\n==== startLast=" + startLast + " > startOffset=" + startOffset + "\n");
            }
            startLast = startOffset;
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
            TokenStream ts = new FilterAposHyphenFrTest(tokenizer);
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
        public AnalyzerLocution()
        {
            super();
        }
        @SuppressWarnings("resource")
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new TokenizerML(); // segment words
            TokenStream ts  = tokenizer;
            ts = new FilterAposHyphenFrTest(ts);
            // ts = new FilterLemmatize(ts); // provide lemma+pos
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

    static public void main(String[] args) throws IOException
    {
        locution();
    }
}
