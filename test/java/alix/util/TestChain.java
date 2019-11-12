package alix.util;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;

public class TestChain
{
  static String zola;
  static {
    try {
      zola = zola();
    }
    catch (URISyntaxException | IOException e) {
      e.printStackTrace();
    }
  }
  static String zola() throws URISyntaxException, UnsupportedEncodingException, IOException 
  {
    String res = "/res/zola.txt";
    Scanner scanner = new Scanner(TestChar.class.getResourceAsStream(res), "UTF-8");
    String text = scanner.useDelimiter("\\A").next();
    scanner.close();
    return text;
  }

  public static void capitalize()
  {
    String test = " Charles-alBert CINGRIA";
    System.out.println(test + " => " + new Chain(test).capitalize());
  }
  
  public static void kwic()
  {
    Chain line = new Chain();
    for (int i = 1; i < 20; i++) {
      int from = i * 1000;
      line.append('|');
      final int right = (i % 13) * 3;
      final int left = ((i+5) % 13) * 3;
      line.append(zola, from, from + right);
      line.prepend(zola, from - left, from);
      line.prepend(zola, from - (2*left), from - left);
      System.out.println((2*left) + "|" + right+" —"+line.replace('\n', ' ')+"—");
      line.reset();
    }
  }


  public static void glob()
  {
    Chain test;
    Chain glob = new Chain("*ent");
    test = new Chain("t");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    test = new Chain("présentement");
    glob = new Chain("*ent*ent");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("présentement");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("prés*ent");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("présentement*");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("present");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
    glob = new Chain("présent");
    System.out.println(glob + " GLOB " + test + " : " + glob.glob(test));
  }

  public static void csv()
  {
    Chain chain;
    chain = new Chain("123456");
    chain.lastDel();
    System.out.println(chain.endsWith("345"));
    Chain line = new Chain(",,C,D,,EPO");
    System.out.println(line + " test CSV");
  }
  
  public static void set()
  {
    HashSet<String> dic = new HashSet<String>(); 
    String text="de en le la les un une des"; 
    for (String token: text.split( " " )) {
      dic.add( token ); 
    } 
    System.out.println( dic ); 
    System.out.println("HashSet<String>.contains(String) "+dic.contains( "des" ) );
    System.out.println("HashSet<String>.contains(Chain) "+dic.contains( new Chain("des") ) );
    
  }

  public static void what()
  {
    Chain line = new Chain("test, toto");
    Chain chain;
    System.out.println();
    System.out.println(Arrays.toString(line.split(',')));
    System.out.println("trim() \"     \" \"" + new Chain("     ").trim(" ") + "\"");
    System.out.println("// Illustration of char data shared between two terms");
    line = new Chain("01234567890123456789"); System.out.println("line: \"" + line + "\""); 
    Chain span = new Chain(); 
    // span.link(line, 3, 4);
    System.out.println("span=new Chain(line, 3, 4): \"" + span + "\"");
    span.setCharAt(0, '-'); System.out.println("span.setCharAt(0, '-') ");
    System.out.println("line: \"" + line + "\""); System.out.println("span: \"" + span + "\""); 
    System.out.println("Test comparesTo()=" + span.compareTo(new Chain("-456")) + " equals()=" + span.equals("-456"));
    System.out.println(span.append("____") + ", " + line); 
    for (char c = 33; c < 100; c++) span.append(c); 
    System.out.println("span: \"" + span + "\"");
    System.out.println("line: \"" + line + "\"");
    System.out.print("Testing equals()"); 
    long time = System.nanoTime(); // test equals perf with a long  String 
    String text = "java - CharBuffer vs. char[] - Stack Overflow stackoverflow.com/questions/294382/charbuffer-vs-char Traduire cette page 16 nov. 2008 - No, there's really no reason to prefer a CharBuffer in this case. In general, though ..... P.S If you use a backport remember to remove it once you catch up to the version containing the real version of the backported code."; 
    chain = new Chain(text); 
    chain.last('p'); // modify the last char for (int
    System.out.print(chain.equals(text)); System.out.println(" " + ((System.nanoTime() - time) / 1000000) + " ms");
  }
  
  public void copy()
  {
    Chain t = new Chain();
    System.out.println( t.copy( "abcde", 2, 1 ) ); 
    t.reset();
    for ( char c=33; c < 100; c++) t.append( c ); 
    System.out.println( t ); 
    t.reset(); 
    for ( int i=0; i < 100; i++) {
      t.append( " "+i ); 
    }
    System.out.println( t );
  }

  public static void main(String[] args)
  {
    kwic();
  }

}
