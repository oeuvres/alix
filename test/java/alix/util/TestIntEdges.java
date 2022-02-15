package alix.util;

public class TestIntEdges
{
    public static void go()
    {
        EdgeQueue edges = new EdgeQueue(false);
        edges.push(0, 0);
        edges.push(0, 1);
        edges.push(0, 1);
        edges.push(1, 0);
        edges.push(1, 0);
        edges.push(0, 2);
        edges.push(1, 2);
        System.out.println(edges.top());
    }
    
    public static void cluster()
    {
        EdgeQueue edges = new EdgeQueue(false);
        edges.declust();
        edges.clust(0);
        System.out.println("clust(0) " + edges.top());
        edges.clust(0);
        System.out.println("clust(0) " +edges.top());
        edges.clust(1);
        System.out.println("clust(1) " +edges.top());
        edges.clust(2);
        System.out.println("clust(2) " +edges.top());
        
    }
    public static void main(String[] args) throws Exception
    {
      go();
      cluster();
    }

}
