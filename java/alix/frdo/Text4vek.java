package alix.frdo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import alix.fr.Lexik;
import alix.fr.Tokenizer;
import alix.fr.Lexik.LexEntry;
import alix.fr.dic.Tag;
import alix.util.Occ;
import alix.util.Chain;

/**
 * Optimise du texte pour la vectorisation
 * 
 * @author glorieux-f
 *
 */
public class Text4vek
{
    static public void parse(final String txt, Writer writer) throws IOException {
        int length = txt.length();
        // réécrire proprement le texte
        Tokenizer toks = new Tokenizer(txt, false);
        Occ occ;
        while ((occ = toks.word()) != null) {
            if (occ.tag().isPun()) {
                if (occ.tag().equals(Tag.PUNdiv)) {
                    writer.append("\n");
                }
                continue;
            }
            if (occ.tag().isName()) {
                writer.append(occ.tag().label());
            }
            else if (occ.tag().isNum()) {
                writer.append(occ.tag().label());
            }
            else if ((occ.tag().equals(Tag.VERB) || occ.tag().isAdj()) && !occ.lem().isEmpty()) {
                writer.append(occ.lem().replace(' ', '_'));
            }
            else if (!occ.tag().equals(Tag.NULL)) {
                writer.append(occ.orth().replace(' ', '_'));
            }
            // unknown word, especially -t-
            else {
            }
            writer.append(' ');
        }
        writer.flush();
        writer.close();
    }


    public static void main(String[] args) throws IOException
    {
        if (args.length < 2) {
            System.out.println("java -cp \"lib/*\" alix.frdo.Text4vek src dst");
            System.exit(0);
        }
        String src = args[0];
        String dst = args[1];
        String text = new String(Files.readAllBytes(Paths.get(src)), StandardCharsets.UTF_8);
        System.out.println(src + " > " + dst);
        PrintWriter out = new PrintWriter(dst);
        parse(text, out);
    }

}
