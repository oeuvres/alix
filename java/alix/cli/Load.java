package alix.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.xml.sax.SAXException;

import alix.lucene.Alix;
import alix.lucene.SrcFormat;
import alix.lucene.XMLIndexer;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.analysis.FrDics;
import alix.util.Dir;

public class Load {
  public static String APP = "Alix";
  static public void index(File propsFile, int threads) throws IOException, NoSuchFieldException, ParserConfigurationException, SAXException, InterruptedException, TransformerException 
  {
    String name = propsFile.getName().replaceFirst("\\..+$", "");
    if (!propsFile.exists()) throw new FileNotFoundException("\n  ["+APP+"] "+propsFile.getAbsolutePath()+"\nProperties file not found");
    Properties props = new Properties();
    try {
      props.loadFromXML(new FileInputStream(propsFile));
    }
    catch (InvalidPropertiesFormatException e) {
      throw new InvalidPropertiesFormatException("\n  ["+APP+"] "+propsFile+"\nXML error in properties file\n"
          +"cf. https://docs.oracle.com/javase/8/docs/api/java/util/Properties.html");
    }
    catch (IOException e) {
      throw new IOException("\n  ["+APP+"] "+propsFile.getAbsolutePath()+"\nProperties file not readable");
    }
    
    String prop;
    ArrayList<String> globs = new ArrayList<String>();
    String key;
    
    key = "srclist";
    prop = props.getProperty(key);
    if (prop != null) {
      File file = new File(prop);
      if (!file.isAbsolute()) file = new File(propsFile.getParentFile(), prop);
      if (!file.exists()) {
        throw new FileNotFoundException("File list <entry key=\"" + key + "\">" + prop + "</entry>, resolved as " + file.getAbsolutePath());
      }
      File base = file.getCanonicalFile().getParentFile();
      List<String> lines = Files.readAllLines(file.toPath());
      for (int i = 0; i < lines.size(); i++) {
        String glob = lines.get(i);
        if (glob.startsWith("#")) continue;
        if (!new File (glob).isAbsolute()) globs.add(new File(base, glob).toString());
        else globs.add(glob);
      }
    }
    else {
      String src = props.getProperty("src");
      
      if (src == null) throw new NoSuchFieldException("\n  ["+APP+"] "+propsFile+"\nan src entry is needed, to have path to index"
          + "\n<entry key=\"src\">../corpus1/*.xml;../corpus2/*.xml</entry>");
      String[] blurf = src.split(" *[;:] *");
      // resolve globs relative to the folder of the properties field
      File base = propsFile.getCanonicalFile().getParentFile();
      for (String glob: blurf) {
        if (!new File (glob).isAbsolute()) globs.add(new File(base, glob).toString());
        else globs.add(glob);
      }
    }
    // test here if it's folder ?
    long time = System.nanoTime();
    

    key = "dicfile";
    prop = props.getProperty(key);
    if (prop != null) {
      File dicfile = new File(prop);
      if (!dicfile.isAbsolute()) dicfile = new File(propsFile.getParentFile(), prop);
      if (!dicfile.exists()) {
        throw new FileNotFoundException("Local dictionary <entry key=\"" + key + "\">" + prop + "</entry>, resolved as " + dicfile.getAbsolutePath());
      }
      FrDics.load(dicfile);
    }

    
    File dstdir;
    prop = props.getProperty("dstdir");
    if (prop != null) {
      dstdir = new File(prop);
      if (!dstdir.isAbsolute()) dstdir = new File(propsFile.getParentFile(), prop);
    }
    else {
      dstdir = propsFile.getParentFile();
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
    // command line, cache doesn’t matter
    Alix alix = Alix.instance( name, tmpPath, new FrAnalyzer(), null);
    // Alix alix = Alix.instance(path, "org.apache.lucene.analysis.core.WhitespaceAnalyzer");
    IndexWriter writer = alix.writer();
    XMLIndexer.index(writer, globs.toArray(new String[globs.size()]), SrcFormat.tei, threads);
    System.out.println("["+APP+"] "+name+" Merging");
    writer.commit();
    writer.close(); // close lucene index before indexing rail (for coocs)
    // pre index text fields for 
    prop = props.getProperty("textfields");
    if (prop != null && !prop.trim().equals("")) {
      for (String field: prop.split("[ \t,;]+")) {
        FieldInfo info = alix.info(field);
        if (info == null) {
          System.out.println("["+APP+"] "+name+". \""+field+"\" is not known as a field");
          continue;
        }
        IndexOptions options = info.getIndexOptions();
        if (options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS && options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
          System.out.println("["+APP+"] "+name+". Field \""+field+"\" has no positions indexed for cooc");
          continue;
        }
        
        System.out.println("["+APP+"] "+name+". Cooc indexation for field: "+field);
        alix.fieldRail(field);
      }
    }
    
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
