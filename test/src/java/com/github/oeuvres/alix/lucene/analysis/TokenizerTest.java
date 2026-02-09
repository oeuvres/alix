package com.github.oeuvres.alix.lucene.analysis;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
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
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
// import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
// import org.apache.lucene.analysis.tokenattributes.TypeAttribute;


import com.github.oeuvres.alix.common.Upos;
import static com.github.oeuvres.alix.common.Upos.*;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.CharsAttImpl;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.LemAtt;
import com.github.oeuvres.alix.lucene.analysis.tokenattributes.OrthAtt;
import com.github.oeuvres.alix.util.Char;
import com.github.oeuvres.alix.util.Dir;

public class TokenizerTest {

    
    

    static public void main(String[] args) throws IOException
    {
        Path path = Paths.get("target/text.txt");
        Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        // Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        List<Path> paths = Dir.ls("D:/code/piaget_xml/piaget1923*.xml");
        // Writer w = Files.newBufferedWriter(Paths.get(""), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Tokenizer toks = new TokenizerML();
        // Tokenizer toks = new StandardTokenizer();
        final CharTermAttribute termAttribute = toks.addAttribute(CharTermAttribute.class);
        final FlagsAttribute flagsAttribute = toks.addAttribute(FlagsAttribute.class);
        for(Path p: paths) {
            out.write("#" + p +'\n');
            BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8);
            toks.setReader(reader);
            toks.reset();
            while(toks.incrementToken()) {
                final int flags = flagsAttribute.getFlags();
                if (flags != TOKEN.code) continue;
                char[] chars = termAttribute.buffer();
                chars[0] = Char.toLower(chars[0]);
                out.write(termAttribute.buffer(), 0 , termAttribute.length());
                out.write('\n');
            }
            toks.close();
        }
        out.close();
    }
}
