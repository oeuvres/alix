package site.oeuvres.util;

import java.util.Arrays;
import java.util.Scanner;

/**
 * Efficient Object to handle a sliding window, 
 * mainly used on a token stream (mutable strings),
 * works like a circular array.
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class StringSlider {
  /** Data of the sliding window */
  private final StringBuffer[] data;
  /** Size of left wing */
  private final int left; 
  /** Size of right wing */
  private final int right; 
  /** Size of the widow */
  private final int width;
  /** Index of center cell */
  private int center;
  
  /** 
   * Constructor, init data
   */
  public StringSlider(final int left, final int right) 
  {
    this.left = left;
    this.right = right;
    width = left + right + 1;
    center = left;
    data = new StringBuffer[width];
    // Arrays.fill will repeat a reference to the same object but do not create it 
    for (int i=0; i<width; i++) data[i] = new StringBuffer();
  }
  /**
   * Get a value by index, positive or negative, relative to center
   * 
   * @param pos
   * @return
   */
  public String get(final int pos) 
  {
    int i = (((center + pos) % width) + width) % width;
    return data[i].toString();
  }
  /**
   * Add a word by the end
   */
  public String addRight(final String value) 
  {
    // modulo in java produce negatives
    center = (((center + 1) % width) + width) % width;
    int i = (((center + right) % width) + width) % width;
    String ret = data[i].toString();
    data[i].setLength(0);
    data[i].append(value);
    return ret;
  }
  /**
   * Show window content
   */
  public String toString() {
    StringBuffer sb = new StringBuffer();
    for (int i = -left; i <= right; i++) {
      if (i == 0) sb.append( " <" );
      sb.append( get(i) );
      if (i == 0) sb.append( "> " );
      else if (i == right);
      else if (i == -1);
      else sb.append( " " );
    }
    return sb.toString();
  }
  /**
   * Test the Class
   * @param args
   */
  public static void main(String args[]) 
  {
    String text = "Son amant emmène un jour O se promener dans un quartier où"
      + " ils ne vont jamais, le parc Montsouris, le parc Monceau. À l’angle du"
      + " parc, au coin, d’une rue où il n’y a jamais de station de taxis, "
      + " après qu’ils se sont promenés dans le parc, et assis côte à côte au"
      + " bord d’une pelouse, ils aperçoivent une voiture, avec un compteur,"
      + " qui ressemble à un taxi. « Monte », dit-il. Elle monte. Ce n’est pas"
      + " loin du soir, et c’est l’automne. Elle est vêtue comme elle l’est"
      + " toujours : des souliers avec de hauts talons, un tailleur à jupe"
      + " plissée, une blouse de soie, et pas de chapeau. Mais de grands gants"
      + " qui montent sur les manches de son tailleur, et elle porte dans son"
      + " sac de cuir ses papiers, sa poudre et son rouge. Le taxi part doucement,"
      + " sans que l’homme ait dit un mot au chauffeur. Mais il ferme, à droite"
      + " et à gauche, les volets à glissière sur les vitres et à l’arrière ;"
      + " elle a retiré ses gants, pensant qu’il veut l’embrasser, ou qu’elle le caresse."
    ;
    StringSlider win = new StringSlider(2, 5);
    @SuppressWarnings("resource")
    Scanner s = new Scanner(text).useDelimiter("\\PL+");
    while(s.hasNext()) {
      win.addRight(s.next());
      System.out.println(win);
    }
    s.close();
  }
}
