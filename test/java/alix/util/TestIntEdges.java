package alix.util;

public class TestIntEdges
{
    public static void go()
    {
        IntEdges edges = new IntEdges(false);
        edges.add(0, 0);
        edges.add(0, 1);
        edges.add(0, 1);
        edges.add(1, 0);
        edges.add(1, 0);
        edges.add(0, 2);
        edges.add(1, 2);
        System.out.println(edges.top());
    }
    public static void main(String[] args) throws Exception
    {
      go();
    }

}
