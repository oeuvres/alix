package alix.lucene.search;

public class TestHiliteFormatter
{
  public static void main(String[] args) {
    String html;
    StringBuilder dest = new StringBuilder();
    dest.append("<pas touche!>");
    
    html = "<p>start broken tag";
    System.out.println("APPEND:"+html);
    HiliteFormatter.detag(dest, html, 1, html.length());
    System.out.println(dest);
    
    dest.setLength(13);
    html = "  inside <BOO>tag";
    System.out.println("APPEND:"+html);
    HiliteFormatter.detag(dest, html, 2, html.length());
    System.out.println(dest);

    dest.setLength(13);
    html = "  inside <BOO>tag<another>";
    System.out.println("APPEND:"+html);
    HiliteFormatter.detag(dest, html, 2, html.length());
    System.out.println(dest);

    dest.setLength(13);
    html = "  end broken tag<bad tag";
    System.out.println("APPEND:"+html);
    HiliteFormatter.detag(dest, html, 2, html.length());
    System.out.println(dest);

    dest.setLength(13);
    html = "start>ça <tag> marche !<bad";
    System.out.println("APPEND:"+html);
    HiliteFormatter.detag(dest, html, 0, html.length());
    System.out.println(dest);
  }
}
