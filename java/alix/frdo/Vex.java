package alix.frdo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import alix.util.Char;
import alix.util.IntOMap;
import alix.util.IntRoller;
import alix.util.IntVek;
import alix.util.Term;
import alix.util.Top;
import alix.util.DicFreq.Entry;
import alix.util.DicVek.SimRow;

/**
 * Create a simple matrix of word embeddings 
 * 1) build the dictionary of the text in order of frequence, to have a word<>index hashmap
 * 2) create the square matrix word x word
 * 3) store the dic and the matrix
 * 
 * @author fred
 *
 */
public class Vex 
{
    String srcFile;
    HashMap<String, Integer> byString;
    String[] byIndex;
    /** Vectors of co-occurences for each term of dictionary */
    private IntVek[] mat;
    double[] magnitudes;
    public Vex(String srcFile) {
        this.srcFile = srcFile;
    }
    public long freqs(String fileName) throws FileNotFoundException, IOException
    {
        int minFreq = 5;
        String l;
        char c;
        Term term = new Term();
        Entry entry;
        String label;
        long wc = 0;
        HashMap<String, Entry> freqs = new HashMap<String, Entry>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(srcFile), StandardCharsets.UTF_8)) {
            while ((l = br.readLine()) != null) {
                // loop on all chars and build words
                int length = l.length();
                for(int i=0; i <= length ; i++) {
                    if (i == length) c = ' ';
                    else c = l.charAt(i);
                    if (Char.isLetter(c)) {
                        term.append(c);
                        continue;
                    }
                    if (term.isEmpty()) continue;
                    // got a word
                    wc++;
                    entry = freqs.get(term);
                    if (entry == null) {
                        label = term.toString();
                        freqs.put(label, new Entry(label));
                    } else {
                        entry.inc();
                    }
                    term.reset();
                }
            }
        }
        List<Entry> list = new ArrayList<Entry>(freqs.values());
        Collections.sort(list);
        int size = list.size();
        byString = new HashMap<String, Integer>();
        ArrayList<String> freqlist = new ArrayList<String>();
        int count;
        try (
            OutputStreamWriter writer = new OutputStreamWriter(
               new FileOutputStream(fileName), StandardCharsets.UTF_8
            )
        ) {
            System.out.println(list.get(0).count);
            for (int i=0;i < size; i++) {
                entry = list.get(i);
                count = entry.count.get();
                if (count <= minFreq) break;
                byString.put(entry.label, i);
                freqlist.add(entry.label);
                writer.write(entry.label+"\t"+count+"\n");
            }
        }
        byIndex = new String[freqlist.size()];
        byIndex = freqlist.toArray(byIndex);
        return wc;
    }
    public void fill(String fileName) throws IOException
    {
        int rows = byString.size();
        mat = new IntVek[rows];
        int cols = byString.size();
        int left = -1;
        int right = 1;
        IntRoller slider = new IntRoller(-1, +1);
        int size = slider.size();
        for (int i=0; i < size; i++) slider.push(-1);
        Term term = new Term();
        String l;
        char c;
        Integer key;
        int length;
        try (BufferedReader br = Files.newBufferedReader(Paths.get(srcFile), StandardCharsets.UTF_8)) {
            while ((l = br.readLine()) != null) {
                length = l.length();
                for(int i=0; i <= length ; i++) {
                    if (i == length) c = ' ';
                    else c = l.charAt(i);
                    if (Char.isLetter(c)) {
                        term.append(c);
                        continue;
                    }
                    if (term.isEmpty()) continue;
                    // got a word
                    key = byString.get(term);
                    term.reset();
                    if (key == null) key = -1;
                    slider.push(key);
                    int row = slider.get(0);
                    if (row < 0) continue;
                    IntVek vec = mat[row];
                    int col;
                    for (int pos = left; pos <= right; pos++) {
                        if (pos == 0) continue;
                        col = slider.get(pos);
                        if (col < 0) continue;
                        vec.inc(col);
                    }
                }
            }
        }
        cosPrep();
    }
    
    private void cosPrep()
    {
        int height = mat.length;
        double mag;
        int value;
        long zeros = 0;
        IntVek vec;
        for(int row = 0; row < height; row++) {
            mag = 0;
            vec = mat[row];
            // square root values
            while(vec.next()) {
                value = vec.value();
                value = (int) Math.ceil(Math.sqrt(value));
                vec.set(value);
            }
        }
    }
    public Top<String> distance(String word)
    {
        Top<String> top = new Top<String>(60);
        Integer wordRow = byString.get(word);
        if (wordRow == null) {
            System.out.println("Word unknown: "+word);
            return null;
        }
        int[] vec = mat[wordRow];
        int height = mat.length;
        int width = mat[0].length;
        double sim;
        long dotprod;
        for (int row = 0; row < height; row++) {
            int[] vec2 = mat[row];
            dotprod = 0;
            for (int col = 0; col < width; col++) {
                dotprod += vec[col] * vec2[col];
            }
            if (dotprod < 0)  {
                System.out.println(byIndex[row]);
            }
            sim = dotprod / (magnitudes[wordRow] * magnitudes[row]);
            top.push(sim, byIndex[row]);
        }
        return top;
    }


    public class Entry implements Comparable<Entry>
    {
        /** The String form of Term */
        private final String label;
        /** A counter */
        private AtomicInteger count = new AtomicInteger(1);
        public Entry(String label)
        {
            this.label = label;
        }
        public int inc()
        {
            return count.incrementAndGet();
        }
        @Override
        /**
         * Default comparator for term informations,
         */
        public int compareTo(Entry o)
        {
          return (o.count.get() - count.get() );
        }
    }
    public static void main(String[] args) throws Exception
    {
        /* This will return Long.MAX_VALUE if there is no preset limit */
        long maxMemory = Runtime.getRuntime().maxMemory();
        /* Maximum amount of memory the JVM will attempt to use */
        System.out.println("Maximum memory (bytes): " + 
        (maxMemory == Long.MAX_VALUE ? "no limit" : maxMemory));
        //
        long start = System.nanoTime();
        // Vex vex = new Vex("/home/fred/code/presse/txt/zola.txt");
        Vex vex = new Vex(args[0]);
        String name = new File(args[0]).getName();
        if (name.lastIndexOf(".") > 0) name = name.substring(0, name.lastIndexOf("."));
        long wc = vex.freqs(name+".dic");
        System.out.println("Dico, "+wc+" occurrences, "+vex.byIndex.length+" "+vex.byString.size()+" mots en " + ((System.nanoTime() - start) / 1000000) + " ms");
        /* Total amount of free memory available to the JVM */
        System.out.println("Free memory (bytes): " + 
        Runtime.getRuntime().freeMemory());
        start = System.nanoTime();
        vex.fill(name+".mat");
        System.out.println("Matrice en " + ((System.nanoTime() - start) / 1000000) + " ms");
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.println("");
            System.out.print("Mot : ");
            String word = keyboard.readLine().trim();
            word = word.trim();
            if (word.isEmpty())
              System.exit(0);
            start = System.nanoTime();
            System.out.println("SIMINYMES (cosine) ");
            boolean first = true;
            Top<String> top = vex.distance(word);
            System.out.println(top);
        }
    }
}
