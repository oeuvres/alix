package alix.frdo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;

import org.json.JSONArray;
import org.json.JSONObject;

import alix.fr.Lexik;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.fr.Lexik.LexEntry;
import alix.util.Occ;
import alix.util.Chain;

public class Presse
{
  /** Faux préfixes */
  public static final HashSet<String> FALSEPREF = new HashSet<String>();
  public static final HashSet<String> ALERT = new HashSet<String>();
  static {
    for (String w : new String[] { "amen", "ar", "ban", "archie", "chie", "com", "con", "cul", "da", "dé", "dés", "do",
        "euro", "fra", "frai", "gent", "hom", "ide", "ides", "in", "ité", "lé", "lés", "mach", "mage", "méta", "mi",
        "nique", "ord", "pie", "ré", "réf", "ron", "sen", "sil", "té", "teille" })
      FALSEPREF.add(w);
    for (String w : new String[] { "FILLES", "GENS" })
      ALERT.add(w);
  }
  String coll;
  String journal;
  StringBuffer sb;
  final File srcDir;
  final File dstFile;
  BufferedWriter writer;

  public Presse(File srcDir, File dstFile) throws IOException
  {
    this.srcDir = srcDir;
    this.dstFile = dstFile;
    dstFile.getParentFile().mkdirs();
    Charset charset = Charset.forName("UTF-8");
    writer = Files.newBufferedWriter(dstFile.toPath(), charset);
    sb = new StringBuffer();
  }

  public void go() throws IOException, ParseException
  {
    walk(srcDir);
    parse(sb.toString(), writer);
  }

  @SuppressWarnings("unlikely-arg-type")
  static public void parse(final String txt, Writer writer) throws IOException
  {
    // réécrire proprement le texte
    Tokenizer toks = new Tokenizer(txt, false);
    Occ occ;
    Chain last = new Chain();
    while ((occ = toks.word()) != null) {
      if (occ.tag().isPun()) {
        last.reset();
        if (occ.tag().equals(Tag.PUNdiv)) writer.append("\n");
        continue;
      }
      if (occ.tag().equals(Tag.NULL) || FALSEPREF.contains(occ.orth())) {
        if (last.isEmpty()) {
          last.append(occ.orth());
          continue;
        }
        last.append(occ.orth()).toLower();
        LexEntry entry = Lexik.WORD.get(last);
        if (entry == null) {
          last.reset();
          continue;
        }
        occ.orth(last);
        occ.lem(entry.lem);
        occ.tag(entry.tag.code());
      }
      last.reset();
      if (occ.tag().isName()) {
        writer.append(occ.tag().label());
      }
      else if (occ.tag().isNum()) {
        writer.append(occ.tag().label());
      }
      else if ((occ.tag().equals(Tag.VERB) || occ.tag().isAdj()) && !occ.lem().isEmpty()) {
        writer.append(occ.lem().replace(' ', '_'));
      }
      else if (occ.tag().equals(Tag.NULL)) {
        System.out.println(occ.graph());
      }
      else {
        writer.append(occ.orth().replace(' ', '_'));
      }
      writer.append(' ');
    }
    writer.flush();
    writer.close();
  }

  public void walk(File dir) throws IOException, ParseException
  {
    File[] ls = dir.listFiles();
    Arrays.sort(ls);
    for (final File src : ls) {
      if (src.getName().startsWith(".")) continue;
      if (src.isDirectory()) {
        walk(src);
        continue;
      }
      if (!src.getName().endsWith(".fulltext.json")) continue;
      String jsonText = new String(Files.readAllBytes(Paths.get(src.toString())), StandardCharsets.UTF_8);
      json(jsonText);
    }
  }

  public void json(String jsonText) throws ParseException
  {
    JSONObject jsonO = new JSONObject(jsonText);
    // OCR raté, on enregistre quelque chose ?
    if (!jsonO.has("contentAsText")) return;

    String identifier = jsonO.getJSONArray("source").getString(1);
    if (!identifier.startsWith("http")) identifier = jsonO.getJSONArray("identifier").getString(1);
    // String date = jsonO.getJSONArray("date").getString(0);
    // String name = coll + "_" + date; // name

    JSONArray pages = jsonO.getJSONArray("contentAsText");
    for (int p = 0; p < pages.length(); p++) {
      String t = pages.getString(p);
      t = t.replaceAll("\n", "\n¶\n");
      sb.append(t);
      sb.append("\n¶¶\n");
    }
    sb.append("\n\n¶¶¶\n\n");
  }

  static public void process(String jsonDir, String txtDir) throws IOException, ParseException
  {
    String[][] list = { { "l_humanite", "L’Humanité" }, { "l_action_francaise", "L’Action Française" },
        { "la_croix", "La Croix" }, { "le_figaro", "Le Figaro" }, { "le_petit_journal", "Le Petit Journal" },
        { "le_temps", "Le Temps" }, { "le_petit_parisien", "Le petit Parisien" }, { "le_matin", "Le Matin" }, };
    for (String[] row : list) {
      String code = row[0];
      String label = row[1];
      System.out.println(label);
      File titleDir = new File(jsonDir, code);
      if (!titleDir.exists()) continue;
      File dstDir = new File(txtDir, code);
      File[] ls = titleDir.listFiles();
      Arrays.sort(ls);
      for (final File year : ls) {
        if (!year.isDirectory()) continue;
        File dstFile = new File(dstDir, code + '-' + year.getName() + ".txt");
        if (dstFile.exists()) continue;
        System.out.print(year + " > " + dstFile);
        Presse presse = new Presse(year, dstFile);
        long start = System.nanoTime();
        presse.go();
        System.out.println(" " + ((double) (System.nanoTime() - start) / 1000000000.0) + " s.");
      }
    }
  }

  public static void main(String[] args) throws IOException, ParseException
  {
    String jsonDir = "/home/fred/code/presse/json";
    if (args.length > 0) jsonDir = args[0];
    String txtDir = "/home/fred/code/presse/txt";
    if (args.length > 1) txtDir = args[1];
    process(jsonDir, txtDir);
    /*
     * StringWriter sw = new StringWriter();
     * parse("début de cette législature, nous vous avions des créances bancaires. \\\"* '"
     * +
     * " Or, dans -le même filet die -l'Agence Economique, on pouvait lire hier Et notre confrère ajouta"
     * , sw); System.out.println(sw);
     */
  }

}
