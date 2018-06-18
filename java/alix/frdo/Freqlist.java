package alix.frdo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashSet;

import alix.fr.Lexik;
import alix.fr.Lexik.LexEntry;

public class Freqlist {
    public static final HashSet<String> STOPMORE = new HashSet<String>();
    static {
        String[] stopmore = new String[] {
            "</s>",
            "ans",
            "com",
            "dé",
            "do",
            "DETnum",
            "heures",
            "heure",
            "NAME",
            "NAMEauthor",
            "NAMEgod",
            "madame",
            "mademoiselle",
            "monsieur",
            "NAMEorg",
            "NAMEplace",
            "NAMEpers",
            "NAMEpersf",
            "NAMEpersm",
            "p.",
            "ré",
        };
        for (String w : stopmore) STOPMORE.add(w);
    }

    static void read(File src, int words) throws IOException
    {
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(src), StandardCharsets.UTF_8));
        String line;
        String[] cells;
        int limit = 100;
        while ((line = reader.readLine()) != null) {
            cells = line.split(" ");
            if (STOPMORE.contains(cells[0])) continue;
            if (Lexik.isStop(cells[0])) continue;
            LexEntry entry = Lexik.entry(cells[0]);
            if (entry == null) continue;
            if (entry.tag.isVerb()) continue;
            double ppm = Math.round(100000000.0 * Integer.parseInt(cells[1]) / words) / 100;
            // if (ppm < entry.lemfreq * 3) continue;
            // System.out.println(cells[0]+'\t'+cells[1]+"\t"+ppm+"\t"+entry.lemfreq );
            System.out.println(cells[0]);
            if (--limit <= 0) break;
        }
    }
    public static void main(String[] args) throws Exception
    {
        String src = "/home/fred/code/presse/freqlists/";
        
        Title[] titles = new Title[] {
            new Title("l_humanite-19-38.vocab", 183853426),
            new Title("le_temps-19-38.vocab", 281051122),
            new Title("le_petit_parisien-19-38.vocab", 271749536),
            new Title("la_croix-19-38.vocab", 205554560),
            new Title("le_figaro-19-38.vocab", 271541431),
            new Title("l_action_francaise-19-38.vocab", 204021092), 
        };
        int i = 5;
        read(new File(src, titles[i].code), titles[i].words);
    }
    static public class Title
    {
        public final String code;
        public final int words;
        public Title(String code, int words) {
            this.code = code;
            this.words = words;
        }
    }
}
