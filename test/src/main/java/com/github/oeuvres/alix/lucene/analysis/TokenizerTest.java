package com.github.oeuvres.alix.lucene.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import static com.github.oeuvres.alix.common.Upos.*;

import com.github.oeuvres.alix.lucene.analysis.fr.FrenchCliticSplitFilter;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.Dir;

public class TokenizerTest
{

    static public class AposHyphenAnalyzer extends Analyzer
    {
        @Override
        public TokenStreamComponents createComponents(String field)
        {
            final Tokenizer tokenizer = new MLTokenizer();
            TokenStream ts = tokenizer;
            ts = new FrenchCliticSplitFilter(tokenizer);
            return new TokenStreamComponents(tokenizer, ts);
        }

    }

    static public void main(String[] args) throws IOException
    {
        Path path = Paths.get("src/test/test-data/text.txt");
        // Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        List<Path> paths = Dir.ls("D:/code/piaget_xml/piaget*.xml");


        try (
            Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING); 
            Analyzer analyzer = new AposHyphenAnalyzer()
        ) {

            for (Path p : paths) {
                System.out.print("#" + p + '\n');
                out.write("#" + p + '\n');
                try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8);
                        TokenStream ts = analyzer.tokenStream("contents", reader))
                {
                    final CharTermAttribute termAttribute = ts.addAttribute(CharTermAttribute.class);
                    final FlagsAttribute flagsAttribute = ts.addAttribute(FlagsAttribute.class);
                    ts.reset();
                    while (ts.incrementToken()) {
                        final int flags = flagsAttribute.getFlags();
                        if (flags != TOKEN.code)
                            continue;
                        char[] chars = termAttribute.buffer();
                        chars[0] = Char.toLower(chars[0]);
                        out.write(termAttribute.buffer(), 0, termAttribute.length());
                        out.write('\n');
                    }
                    ts.end();
                } // ts.close() happens here (critical), then reader.close()
            }
        }  // analyzer.close() and out.close() happen here
        System.out.println("DONE");
    }
}
