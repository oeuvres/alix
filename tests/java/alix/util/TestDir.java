package alix.util;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.List;

public class TestDir
{
  public static void main(String[] args) throws IOException, ParseException, URISyntaxException
  {
    List<File> ls = Dir.ls("docs/*/*.html");
    System.out.println(ls);
  }
}
