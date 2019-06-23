package alix.frdo;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import javax.imageio.ImageIO;

import alix.fr.Lexik;
import alix.fr.Tokenizer;
import alix.util.DicFreq;
import alix.util.Occ;

/**
 * Reads a text,
 * 
 * @author glorieux-f
 *
 */
public class Text2pix
{
  private static int lexkey = 0;
  static ArrayList<Color> colors = new ArrayList<Color>();
  static {
    int col = 0xFFFFFF;
    while (col > 0) {
      colors.add(new Color(col));
      col -= 130;
    }
    Collections.sort(colors);

    int x = 0;
    int y = 0;
    int width = 660;
    int height = 196;
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    int size = colors.size();
    int rgb;
    for (int i = 0; i < size; i++) {
      rgb = colors.get(i).rgb;
      img.setRGB(x, y, rgb);
      x++;
      if (x >= width) {
        x = 0;
        y++;
      }
    }
    System.out.println(y);
    try {
      ImageIO.write(img, "png", new File("/home/fred/code/pix/colors.png"));
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }
  /** color codes for known words */
  public static HashMap<String, Integer> WORD = new HashMap<String, Integer>((int) (150000 * 0.75));
  static {
    String res = "/alix/fr/dic/word.csv";
    BufferedReader buf = new BufferedReader(
        new InputStreamReader(Lexik.class.getResourceAsStream(res), StandardCharsets.UTF_8));
    String sep = ";";
    String l;
    String[] cells;
    int rgb;
    try {
      buf.readLine(); // skip first line
      while ((l = buf.readLine()) != null) {
        l = l.trim();
        if (l.isEmpty()) continue;
        if (l.charAt(0) == '#') continue;
        cells = l.split(sep);
        if (cells.length < 1) continue;
        cells[0] = cells[0].trim();
        if (WORD.containsKey(cells[0])) continue;
        rgb = colors.get(lexkey).rgb;
        lexkey++;
        WORD.put(cells[0], rgb);
      }
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }

  @SuppressWarnings("unlikely-arg-type")
  public Text2pix(String text, String dst) throws IOException
  {
    int width = 660;
    int x = 0;
    int height = 400;
    int y = 0;
    int imgtype = BufferedImage.TYPE_INT_RGB;
    BufferedImage img = new BufferedImage(width, height, imgtype);
    Tokenizer toks = new Tokenizer(text);
    Occ occ = new Occ();
    Integer rgb;
    // int size = colors.size();
    while ((occ = toks.word()) != null) {
      rgb = WORD.get(occ.orth());
      if (occ.tag().isPun()) {
        rgb = 0x000000;
        continue;
      }
      if (occ.tag().isName()) {
        rgb = 0xFF0000;
        continue;
      }
      if (rgb == null) {
        rgb = colors.get(lexkey).rgb;
        WORD.put(occ.orth().toString(), rgb);
        lexkey++;
      }
      if (y >= height) {
        continue;
        /*
         * BufferedImage oldimg = img; img = new BufferedImage(width, height * 2,
         * imgtype); img.setRGB(0, 0, width, height, oldimg.getRGB(0, 0, width, height,
         * null, 0, width), 0, width); height = height * 2;
         */
      }
      // rgb = (int)(Math.random() * 0x1000000);
      img.setRGB(x, y, rgb);
      x++;
      if (x >= width) {
        x = 0;
        y++;
      }
    }
    ImageIO.write(img, "png", new File(dst));
  }

  static void cats(String text)
  {
    DicFreq dic = new DicFreq();
    Tokenizer toks = new Tokenizer(text);
    Occ occ = new Occ();
    while ((occ = toks.word()) != null) {
      if (occ.tag().isPun()) continue;
      else if (occ.tag().isName()) dic.inc("NAME");
      else dic.inc(occ.tag().label());
    }
    System.out.println(dic);
  }

  private static class Color implements Comparable<Color>
  {
    int rgb;
    final Double light;

    Color(int rgb)
    {
      this.rgb = rgb;
      int red = rgb >> 16;
      int green = (rgb & 0x00FF00) >> 8;
      int blue = rgb & 0x0000FF;
      light = 0.299 * red + 0.587 * green + 0.114 * blue;
    }

    @Override
    public int compareTo(Color col)
    {
      return col.light.compareTo(light);
    }
  }

}
