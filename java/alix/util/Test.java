package alix.util;

import java.io.*;

public class Test
{
  public static void main2(String args[])
  {
    if (args.length != 1) {
      System.err.println("missing filename");
      System.exit(1);
    }
    try {
      FileInputStream fis = new FileInputStream(args[0]);
      BufferedInputStream bis = new BufferedInputStream(fis);
      int cnt = 0;
      int b;
      while ((b = bis.read()) != -1) {
        if (b == '\n') cnt++;
      }
      bis.close();
      System.out.println(cnt);
    }
    catch (IOException e) {
      System.err.println(e);
    }
  }


  public static void main(String args[])
  {
    if (args.length != 1) {
      System.err.println("missing filename");
      System.exit(1);
    }
    try {
      FileInputStream fis = new FileInputStream(args[0]);
      byte buf[] = new byte[2048];
      int cnt = 0;
      int n;
      while ((n = fis.read(buf)) != -1) {
        for (int i = 0; i < n; i++) {
          if (buf[i] == '\n') cnt++;
        }
      }
      fis.close();
      System.out.println(cnt);
    }
    catch (IOException e) {
      System.err.println(e);
    }
  }
}
