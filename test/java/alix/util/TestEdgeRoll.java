package alix.util;
/**
 * Test the Spabn collector
 * @author neuchatel
 *
 */
public class TestEdgeRoll
{
    public static void go()
    {
        long start = System.nanoTime();
        final int N = 20000000;
        final int voc = 10000;
        final int[] values = new int[] {
            1,   2,  3,  4,  5,  6,  7,  8,  9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50
        };
        final int nodeLen = values.length;
        final int dist = 5;
        EdgeRoll span = new EdgeRoll(values, dist);
        for (int position = 0; position < N; position++) {
            final int value = (int)(Math.random() * voc);
            // int value= position % voc;
            // System.out.print(" " + value);
            span.push(position, value);
        }
        System.out.println();
        System.out.println( ""+( (System.nanoTime()-start)/1000000)+"ms" );
        System.out.println(span.edges());
    }
    
    public static void main(String[] args) throws Exception
    {
      go();
    }

}
