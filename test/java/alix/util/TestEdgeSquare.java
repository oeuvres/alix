package alix.util;
/**
 * Test the Spabn collector
 * @author neuchatel
 *
 */
public class TestEdgeSquare
{
    public static void go()
    {
        final int[] words = new int[]{10, 20, 30, 40, 50};
        EdgeSquare square = new EdgeSquare(words, false);
        final int nodeLen = words.length;

        for (int line=0; line < nodeLen; line++) {
            for (int col=0; col < nodeLen; col++) {
                square.set(line, col, (int)(Math.random() * 5));
            }
        }
        for (Edge edge: square) {
            System.out.println(edge);
        }
    }
    
    public static void main(String[] args) throws Exception
    {
      go();
    }

}
