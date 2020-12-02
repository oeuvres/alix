package alix.lucene.analysis;

import java.io.IOException;

import alix.lucene.analysis.CharsNet;
import alix.lucene.analysis.CharsNet.Edge;
import alix.lucene.analysis.CharsNet.Node;
import alix.lucene.analysis.tokenattributes.CharsAtt;


public class TestCharsNet
{
  public static void directed()
  {
    CharsNet net = new CharsNet(3, true);
    String[] words = "A B C D C B A B".split("\\s+");
    CharsAtt token = new CharsAtt();
    for (String w: words) {
      token.setEmpty().append(w);
      net.inc(token);
    }
    
    
    Edge[] edges = net.edges();
    for (Edge e: edges) {
      System.out.println(e);
    }
    System.out.println("{");
    System.out.println("  edges: [");
    boolean first = true;
    int max = Math.min(100, edges.length);
    for (int i = 0; i < max; i++) {
      Edge edge = edges[i];
      if (first) first = false;
      else System.out.println(", ");
      // {id:'e384606', source:'n907', target:'n4225', size:4, color:'rgba(192, 192, 192, 0.4)', type:'halo'}
      System.out.print("    {id:'e" + edge.id() + "', source:'n" + edge.source() + "', target:'n" + edge.target() + "', size:" + edge.count() + "}");
    }
    System.out.println("\n  ],");
    Node[] nodes = net.nodes();
    System.out.println("  nodes: [");
    first = true;
    max = Math.min(100, nodes.length);
    for (int i = 0; i < max; i++) {
      Node node = nodes[i];
      if (first) first = false;
      else System.out.println(", ");
      // {id:'n204', label:'coeur', x:-16, y:99, size:86, color:'hsla(0, 86%, 42%, 0.95)'},
      System.out.print("    {id:'n" + node.id() + "', label:'" + node.label() + "', size:" + node.count() + "}");
    }
    System.out.println("\n  ]");
    System.out.println("}");
  }
  public static void main(String[] args) throws IOException
  {
    directed();
  }

}
