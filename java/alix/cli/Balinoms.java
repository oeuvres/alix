package alix.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import alix.fr.Tag;
import alix.lucene.analysis.FrLemFilter;
import alix.lucene.analysis.FrPersnameFilter;
import alix.lucene.analysis.FrTokenizer;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

@Command(name = "Balinoms", description = "Tag names in an XML/TEI file", mixinStandardHelpOptions = true)
public class Balinoms implements Callable<Integer> {
    static class Name implements Comparable<Name> {
        int count = 1;
        int tag = Tag.NAME.flag;
        String form;

        Name(final String form) {
            this.form = form;
        }

        public void inc() {
            count++;
        }

        @Override
        public int compareTo(Name o) {
            return o.count - count;
        }
    }

    static class AnalyzerNames extends Analyzer {

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            final Tokenizer source = new FrTokenizer();
            TokenStream result = new FrLemFilter(source);
            result = new FrPersnameFilter(result);
            return new TokenStreamComponents(source, result);
        }

    }

    static Analyzer anaNoms = new AnalyzerNames();

    Map<String, AtomicInteger> mapAll = new HashMap<>();

    Map<String, AtomicInteger> mapName = new HashMap<>();
    Map<String, AtomicInteger> mapPers = new HashMap<>();
    Map<String, AtomicInteger> mapPlace = new HashMap<>();

    @Parameters(arity = "1..*", description = "au moins un fichier XML/TEI à baliser")
    File[] files;

    @Override
    public Integer call() throws Exception {
        for (final File src : files) {
            String name = src.getName().substring(0, src.getName().lastIndexOf('.'));
            String ext = src.getName().substring(src.getName().lastIndexOf('.'));
            String dest = src.getParent() + "/" + name + "_alix" + ext;
            parse(new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8),
                    new PrintWriter(dest));
            ;
            Files.write(Paths.get(src.getParent() + "/" + name + "_all.tsv"), top(mapAll, -1).getBytes("UTF-8"));
            Files.write(Paths.get(src.getParent() + "/" + name + "_name.tsv"), top(mapName, -1).getBytes("UTF-8"));
            Files.write(Paths.get(src.getParent() + "/" + name + "_place.tsv"), top(mapPlace, -1).getBytes("UTF-8"));
            Files.write(Paths.get(src.getParent() + "/" + name + "_pers.tsv"), top(mapPers, -1).getBytes("UTF-8"));
            System.out.println(src + " > " + dest);
        }
        System.out.println("C’est fini");
        return 0;
    }

    /**
     * Traverser le texte, ramasser les infos, cracher à la fin
     * 
     * @param code
     * @param text
     * @throws IOException
     */
    public void parse(String xml, PrintWriter out) throws IOException {
        TokenStream stream = anaNoms.tokenStream("stats", new StringReader(xml));
        int toks = 0;
        int begin = 0;
        //
        final CharsAtt termAtt = (CharsAtt) stream.addAttribute(CharTermAttribute.class);
        final CharsAtt orthAtt = (CharsAtt) stream.addAttribute(CharsOrthAtt.class);
        final CharsAtt lemAtt = (CharsAtt) stream.addAttribute(CharsLemAtt.class);
        final OffsetAttribute attOff = stream.addAttribute(OffsetAttribute.class);
        final FlagsAttribute attFlags = stream.addAttribute(FlagsAttribute.class);
        try {
            stream.reset();
            // print all tokens until stream is exhausted
            while (stream.incrementToken()) {
                toks++;
                final int flag = attFlags.getFlags();
                // TODO test to avoid over tagging ?
                if (!Tag.NAME.sameParent(flag))
                    continue;
                out.print(xml.substring(begin, attOff.startOffset()));
                begin = attOff.endOffset();
                inc(mapAll, lemAtt);
                if (Tag.NAMEplace.flag == flag) {
                    out.print("<placeName>");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</placeName>");
                    inc(mapPlace, lemAtt);
                }
                // personne
                else if (Tag.NAMEpers.flag == flag || Tag.NAMEauthor.flag == flag || Tag.NAMEfict.flag == flag) {
                    out.print("<persName key=\"" + lemAtt + "\">");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</persName>");
                    inc(mapPers, lemAtt);
                }
                // non repéré supposé personne
                else if (Tag.NAME.flag == flag) {
                    out.print("<persName key=\"" + lemAtt + "\">");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</persName>");
                    inc(mapPers, lemAtt);
                    inc(mapName, lemAtt);
                } else {
                    out.print("<name>");
                    out.print(xml.substring(attOff.startOffset(), attOff.endOffset()));
                    out.print("</name>");
                }

                if (lemAtt.isEmpty())
                    System.out.println("term=" + termAtt + " orth=" + orthAtt + " lem=" + lemAtt);
            }

            stream.end();
        } finally {
            stream.close();
            // analyzer.close();
        }
        out.print(xml.substring(begin));
        out.flush();
        out.close();

    }

    /**
     * Increment dics. This way should limit object creation
     */
    private void inc(final Map<String, AtomicInteger> map, final CharsAtt chars) {
        @SuppressWarnings("unlikely-arg-type")
        AtomicInteger value = map.get(chars);
        if (value == null) {
            map.put(chars.toString(), new AtomicInteger(1));
        } else {
            value.getAndIncrement();
        }
    }

    public String top(final Map<String, AtomicInteger> map, final int limit) {
        StringBuffer sb = new StringBuffer();
        List<Map.Entry<String, AtomicInteger>> list = sort(map);
        Iterator<Map.Entry<String, AtomicInteger>> it = list.iterator();
        int n = 1;
        while (it.hasNext()) {
            Map.Entry<String, AtomicInteger> entry = it.next();
            sb.append(n + ".\t" + entry.getKey() + "\t" + entry.getValue() + "\n");
            if (n == limit)
                break;
            n++;
        }
        return sb.toString();
    }

    private List<Map.Entry<String, AtomicInteger>> sort(Map<String, AtomicInteger> map) {
        List<Map.Entry<String, AtomicInteger>> list = new LinkedList<>(map.entrySet());
        list.sort(new Comparator<Map.Entry<String, AtomicInteger>>() {
            @Override
            public int compare(Entry<String, AtomicInteger> o1, Entry<String, AtomicInteger> o2) {
                int dif = o2.getValue().get() - o1.getValue().get();
                if (dif == 0)
                    return o1.getKey().compareTo(o2.getKey());
                return dif;
            }
        });
        return list;
    }

    /**
     * Test the Class
     * 
     * @param args
     * @throws IOException
     */
    public static void main(String args[]) throws IOException {
        int exitCode = new CommandLine(new Balinoms()).execute(args);
        System.exit(exitCode);
    }

}
