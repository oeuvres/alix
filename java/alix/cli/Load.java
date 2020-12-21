package alix.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.InvalidPropertiesFormatException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.lucene.index.IndexWriter;
import org.xml.sax.SAXException;

import alix.lucene.Alix;
import alix.lucene.SrcFormat;
import alix.lucene.XMLIndexer;
import alix.lucene.analysis.FrAnalyzer;
import alix.util.Dir;

public class Load {
  public static String APP = "Alix";
  static public void index(File file, int threads) throws IOException, NoSuchFieldException, ParserConfigurationException, SAXException, InterruptedException, TransformerException 
  {
    String name = file.getName().replaceFirst("\\..+$", "");
    if (!file.exists()) throw new FileNotFoundException("\n  ["+APP+"] "+file.getAbsolutePath()+"\nProperties file not found");
    Properties props = new Properties();
    try {
      props.loadFromXML(new FileInputStream(file));
    }
    catch (InvalidPropertiesFormatException e) {
      throw new InvalidPropertiesFormatException("\n  ["+APP+"] "+file+"\nXML error in properties file\n"
          +"cf. https://docs.oracle.com/javase/8/docs/api/java/util/Properties.html");
    }
    catch (IOException e) {
      throw new IOException("\n  ["+APP+"] "+file.getAbsolutePath()+"\nProperties file not readable");
    }
    String src = props.getProperty("src");
    if (src == null) throw new NoSuchFieldException("\n  ["+APP+"] "+file+"\nan src entry is needed, to have path to index"
        + "\n<entry key=\"src\">../corpus1/*.xml;../corpus2/*.xml</entry>");
    String[] globs = src.split(" *[;:] *");
    // resolve globs relative to the folder of the properties field
    File base = file.getCanonicalFile().getParentFile();
    for (int i=0; i < globs.length; i++) {
      if (!globs[i].startsWith("/")) globs[i] = new File(base, globs[i]).getCanonicalPath();
    }
    // test here if it's folder ?
    long time = System.nanoTime();
    

    File dstdir;
    String prop = props.getProperty("dstdir");
    if (prop != null) {
      dstdir = new File(prop);
      if (!dstdir.isAbsolute()) dstdir = new File(file.getParentFile(), prop);
    }
    else {
      dstdir = file.getParentFile();
    }

    String tmpName = name+"_new";
    // indexer d'abord dans un index temporaire
    File tmpDir = new File(dstdir, tmpName);
    if (tmpDir.exists()) {
      long modified = tmpDir.lastModified();
      Duration duration = Duration.ofMillis(System.currentTimeMillis() - modified);
      throw new IOException("\n  ["+APP+"] Another process seems indexing till "+duration.toSeconds()+" s.\n" + tmpDir
          + "\nIf you think it’s an error, this folfder should be deleted by you");
    }
    Path tmpPath = tmpDir.toPath();
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        if (!tmpDir.exists()) return;
        System.out.println("Indexation process interrupted, nothing has been modified, temp index will be deleted :\n" + tmpPath);
        try {
          TimeUnit.SECONDS.sleep(1);
          int timeout = 10;
          while (!tmpDir.canWrite()) {
            TimeUnit.SECONDS.sleep(1);
            if(--timeout == 0) throw new IOException("\n  ["+APP+"] Impossible to delete temp index\n" + tmpDir);
          }
          Dir.rm(tmpDir);
          // Encore là ?
          while (tmpDir.exists()) {
            TimeUnit.SECONDS.sleep(1);
            Dir.rm(tmpDir);
            if(--timeout == 0) throw new IOException("\n  ["+APP+"] Impossible to delete temp index\n" + tmpDir);
          }
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
    Alix alix = Alix.instance(tmpPath, new FrAnalyzer());
    // Alix alix = Alix.instance(path, "org.apache.lucene.analysis.core.WhitespaceAnalyzer");
    IndexWriter writer = alix.writer();
    XMLIndexer.index(writer, globs, SrcFormat.tei, threads);
    System.out.println("["+APP+"] "+name+" Merging");
    writer.commit();
    writer.close();
    System.out.println("["+APP+"] "+name+" indexed in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    
    /*
    TimeZone tz = TimeZone.getTimeZone("UTC");
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
    df.setTimeZone(tz);
    */
    String oldName = name+"_old";
    File oldDir = new File(dstdir, oldName);
    File theDir = new File(dstdir, name);
    if (theDir.exists()) {
      if (oldDir.exists()) Dir.rm(oldDir);
      theDir.renameTo(oldDir);
      System.out.println("["+APP+"] For safety, you old index is preserved in folder :\n"+oldDir);
    }
    tmpDir.renameTo(theDir);
  }

  public static void main(String[] args) throws Exception
  {
    if (args == null || args.length < 1) {
      System.out.println("["+APP+"] usage");
      System.out.println("WEB-INF$ java -cp lib/alix.jar bases/base_props.xml");
      System.exit(1);
    }
    int threads = Runtime.getRuntime().availableProcessors() - 1;
    int i = 0;
    try {
      int n = Integer.parseInt(args[0]);
      if (n > 0 && n < threads) threads = n;
      i++;
      System.out.println("["+APP+"] threads="+threads);
    }
    catch (NumberFormatException e) {
      
    }
    if (i >= args.length) {
      System.out.println("["+APP+"] usage");
      System.out.println("WEB-INF$ java -cp lib/alix.jar bases/base_props.xml");
      System.exit(1);
    }
    for(; i < args.length; i++) {
      File file = new File(args[i]);
      if (file.getCanonicalPath().endsWith("WEB-INF/web.xml")) continue;
      index(file, threads);
    }
  }


}
