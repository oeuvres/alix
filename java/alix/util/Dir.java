package alix.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Dir
{
  /**
   * Collect files recursively from a folder. Default regex pattern to select
   * files is : .*\.xml A regex selector can be provided by the path argument.
   * path= "folder/to/index/.*\.tei" Be careful, pattern is a regex, not a glob
   * (don not forger the dot for any character).
   * 
   * @param path
   * @return
   */
  public static List<File> ls(String path)
  {
    ArrayList<File> files = new ArrayList<File>();
    return ls(path, files);
  }

  public static List<File> ls(String path, List<File> files)
  {
    if (files == null) files = new ArrayList<File>();
    File dir = new File(path);
    String re = ".*\\.xml";
    if (!dir.isDirectory()) {
      re = dir.getName();
      dir = dir.getParentFile();
      // if (!dir.isDirectory()) let exception go
    }
    collect(dir, Pattern.compile(re), files);
    return files;
  }

  /**
   * Private collector of files to index.
   * 
   * @param dir
   * @param pattern
   * @return
   */
  private static void collect(File dir, Pattern pattern, final List<File> files)
  {
    File[] ls = dir.listFiles();
    for (File entry : ls) {
      String name = entry.getName();
      if (name.startsWith(".")) continue;
      else if (entry.isDirectory()) collect(entry, pattern, files);
      else if (!pattern.matcher(name).matches()) continue;
      else files.add(entry);
    }
  }
}
