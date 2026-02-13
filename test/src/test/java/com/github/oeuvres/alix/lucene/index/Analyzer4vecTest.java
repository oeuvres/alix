package com.github.oeuvres.alix.lucene.index;

import java.io.PrintWriter;
import java.io.StringReader;
import org.apache.lucene.analysis.Analyzer;

import com.github.oeuvres.alix.cli.Analyze4vec;

public class Analyzer4vecTest
{

    /**
     * Send index loading.
     * 
     * @param args Command line arguments.
     * @throws Exception XML or Lucene errors.
     */
    public static void main(String[] args) throws Exception
    {
        String text = "Je ne puis résumer ici tous les 6 travaux parus depuis cette époque";
        Analyzer analyzer = new Analyze4vec.Analyzer4vec();
        System.out.println("YO ?");
        PrintWriter writer = new PrintWriter(System.out);
        Analyze4vec.unroll(analyzer.tokenStream("", new StringReader(text)), writer);
        writer.flush();
        writer.close();
        analyzer.close();
    }
}
