package alix.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.json.JSONArray;
import org.json.JSONObject;

import alix.lucene.analysis.tokenattributes.CharsAtt;
/**
 * Some useful tools to deal with “Markup Languages” (xml, but also html tag soup)
 */
public class ML
{
  public static final HashMap<String, Character> HTMLENT = new HashMap<String, Character>();
  static {
    BufferedReader buf = new BufferedReader(
        new InputStreamReader(Char.class.getResourceAsStream("htmlent.csv"), StandardCharsets.UTF_8));
    String l;
    String ent;
    Character c;
    int pos;
    try {
      buf.readLine(); // first line is labels
      while ((l = buf.readLine()) != null) {
        l = l.trim();
        if (l.charAt(0) == '#') continue;
        pos = l.indexOf(' ');
        if (pos < 3) continue;
        if (pos + 1 >= l.length()) continue;
        ent = l.substring(0, pos);
        c = l.charAt(pos + 1);
        HTMLENT.put(ent, c);
      }
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /**
   * Get the char from an HTML entity like &amp;gt; or &amp;#128;.
   * Will not work on supplementary char like &amp;Afr; 𝔄  &amp;x1d504; (3 bytes).
   * @param ent
   * @return
   */
  public static char forChar(final String ent)
  {
    Character c = HTMLENT.get(ent);
    if (c == null) return 0;
    return c;
  }

  /**
   * See {@link #forChar(String)}, with a custom mutable String.
   * @param ent
   * @return
   */
  public static char forChar(final Chain ent)
  {
    @SuppressWarnings("unlikely-arg-type")
    Character c = HTMLENT.get(ent);
    if (c == null) return 0;
    return c;
  }

  /**
   * See {@link #forChar(String)}, with a custom implementation of lucene 
   * {@link CharTermAttribute} sharing the same hash fuction as a String.
   * @param ent
   * @return
   */
  public static char forChar(final CharsAtt ent)
  {
    @SuppressWarnings("unlikely-arg-type")
    Character c = HTMLENT.get(ent);
    if (c == null) return 0;
    return c;
  }

  /**
   * Is it a char allowed in an entity code ?
   * @param c
   * @return
   */
  public static boolean isInEnt(char c) 
  {
    if (c > 128) return false;
    return Char.isLetterOrDigit(c);
  }

  /**
   * Provide a text version of an xml excerpt (possibly broken).
   * @param xml
   * @return
   */
  public static String detag(String xml)
  {
    StringBuilder dest = new StringBuilder();
    int start = 0;
    int end = xml.length();
    boolean lt = false, first = true, space = false;
    for (int i = start; i < end; i++) {
      char c = xml.charAt(i);
      switch (c) {
        case '<':
          space = false; // renew space flag
          first = false; // no broken tag at start
          lt = true;
          break;
        case '>':
          lt = false;
          // a broken tag at start, erase what was appended
          if (first) {
            dest.setLength(dest.length() - (i - start));
            first = false;
            break;
          }
          break;
        case ' ':
        case '\n':
        case '\r':
        case '\t':
          if (lt) break; // space inside tag, skip
          if(space) break; // second or more space, skip
          space = true; // stop record space
          dest.append(' ');
          break;
        default:
          if (lt) break; // char in tag, skip
          space = false; // renew space flag
          dest.append(c);
      }
    }
    return dest.toString();
  }

  /**
   * Provide a text version of an xml excerpt (possibly broken).
   * @param xml
   * @return
   */
  public static void appendText(final String xml, int offset, final int amount, final Chain chain)
  {
    int length = xml.length();
    boolean lt = false, first = true, space = false;
    int count = 0;
    while (offset< length) {
      char c = xml.charAt(offset);
      switch (c) {
        case '<':
          space = false; // renew space flag
          first = false; // no broken tag at start
          lt = true;
          break;
        case '>':
          lt = false;
          // a broken tag at start, erase what was appended
          if (first) {
            chain.lastDel(count);
            count = 0;
            first = false;
            break;
          }
          break;
        case ' ':
        case '\n':
        case '\r':
        case '\t':
          if (lt) break; // space inside tag, skip
          if(space) break; // second or more space, skip
          space = true; // stop record space
          chain.append(' ');
          count++;
          break;
        default:
          if (lt) break; // char in tag, skip
          space = false; // renew space flag
          chain.append(c);
          count++;
      }
      offset++;
      if(count >= amount) break;
    }
  }

  /**
   * Provide a text version of an xml excerpt (possibly broken).
   * @param xml
   * @return
   */
  public static void prependText(final String xml, int offset, final int amount, final Chain chain)
  {
    boolean gt = false, first = true, space = false;
    int count = 0;
    while (offset >= 0) {
      char c = xml.charAt(offset);
      switch (c) {
        case '>':
          space = false; // renew space flag
          first = false; // no broken tag at start
          gt = true;
          break;
        case '<':
          gt = false;
          // a broken tag, erase what was appended
          if (first) {
            chain.firstDel(count);
            count = 0;
            first = false;
            break;
          }
          break;
        case ' ':
        case '\n':
        case '\r':
        case '\t':
          if (gt) break; // space inside tag, skip
          if(space) break; // second or more space, skip
          chain.prepend(' ');
          space = true; // stop record space
          count++;
          break;
        default:
          if (gt) break; // char in tag, skip
          space = false; // renew space flag
          chain.prepend(c);
          count++;
      }
      offset--;
      if(count >= amount) break;
    }
  }

  
  /**
   * Tool to load entities from a json file
   * @param path
   * @throws UnsupportedEncodingException
   * @throws IOException
   */
  static void load(final String path) throws UnsupportedEncodingException, IOException
  {
    String cont = new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
    JSONObject json = new JSONObject(cont);
    List<String> keys = new ArrayList<String>(json.keySet());
    Collections.sort(keys);

    for(String key: keys) {
      if (!key.endsWith(";")) continue;
      JSONObject entry = json.getJSONObject(key);
      String value = entry.getString("characters");
      if ("\n".equals(value)) value="\\n";
      if ("\t".equals(value)) value="\\t";
      //if (value.length() == 1) continue;
      System.out.println(key+" "+value);
      JSONArray points = entry.getJSONArray("codepoints");
      for (int i=0; i < points.length(); i++) {
        int code = points.getInt(i);
        System.out.println("&"+code+";"+" "+value);
        if (code < 16) System.out.println("&x"+String.format("%01x", code)+";"+" "+value);
        if (code < 256) System.out.println("&x"+String.format("%02x", code)+";"+" "+value);
        // if (code < 4096) System.out.println("&x"+String.format("%03x", code)+";"+" "+value);
        if (code < 65536) System.out.println("&x"+String.format("%04x", code)+";"+" "+value);
        if (code > 65535) System.out.println("&x"+String.format("%x", code)+";"+" "+value);
      }
      
    }
  }

}
