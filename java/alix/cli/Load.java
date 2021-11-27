package alix.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.xml.sax.SAXException;

import alix.lucene.Alix;
import alix.lucene.XMLIndexer;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.analysis.FrDics;
import alix.util.Dir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
  name = "alix.cli.Load", 
  description = "Load an XML/TEI corpus in a custom Lucene index for Alix."
)
public class Load implements Callable<Integer>
{
  public static String APP = "Alix";
  
  @Parameters(arity = "1..*", paramLabel = "corpus.xml", description = "base_props.xml — 1 ou more Java/XML/properties describing a document base (label, src…)")
  File[] conflist;
  
  @Option(names = "-threads", description="[expert] Allow more than one thread for indexation")
  int threads;
  
  @Override
  public Integer call() throws Exception {
    for (final File conf : conflist) {
      if (conf.getCanonicalPath().endsWith("WEB-INF/web.xml")) continue;
      index(conf);
    }
    System.out.println("C’est fini");
    return 0;
  }


  
  public void index(File propsFile) throws IOException, NoSuchFieldException, ParserConfigurationException, SAXException, InterruptedException, TransformerException 
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
    ArrayList<File> globs = new ArrayList<>();
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
        if (!new File (glob).isAbsolute()) globs.add(new File(base, glob).getCanonicalFile());
        else globs.add(new File(glob).getCanonicalFile());
      }
    }
    else {
      String src = props.getProperty("src");
      
      if (src == null) throw new NoSuchFieldException("\n  ["+APP+"] "+propsFile+"\nan src entry is needed, to have path to index"
          + "\n<entry key=\"src\">../corpus1/*.xml;../corpus2/*.xml</entry>");
      String[] blurf = src.split(" *[;] *|[\t ]*[\n\r]+[\t ]*");
      // resolve globs relative to the folder of the properties field
      final File base = propsFile.getCanonicalFile().getParentFile();
      for (String glob: blurf) {
        if (glob.trim().equals("")) continue;
        if (File.separatorChar == '\\') glob = glob.replaceAll("[/\\\\]", File.separator+File.separator);
        else glob = glob.replaceAll("[/\\\\]", File.separator);
        if (!new File (glob).isAbsolute()) {
          File dir = base.getAbsoluteFile();
          // glob = new File(glob).toString(); // works for windows on /, but not on linux for \
          if (glob.startsWith("." + File.separator)) glob = glob.substring(2);
          while(glob.startsWith(".." + File.separator)) {
            dir = dir.getParentFile();
            glob = glob.substring(3);
          }
          globs.add(new File(dir, glob));
        }
        else globs.add(new File(glob));
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

    // set a local xsl to generate alix:document
    String xsl = props.getProperty("xsl");
    
    
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
    IndexWriter writer = alix.writer();
    XMLIndexer.index(writer, globs.toArray(new File[globs.size()]), threads, xsl);
    System.out.println("["+APP+"] "+name+" Merging");
    writer.commit();
    writer.close(); // close lucene index before indexing rail (for coocs)
    // pre index text fields for co-occurrences, so that index could stay read only by server
    Collection<String> fields = FieldInfos.getIndexedFields(alix.reader());
    for (String field: fields) {
      FieldInfo info = alix.info(field);
      IndexOptions options = info.getIndexOptions();
      // non text fields, facets
      if (options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS && options != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
        // System.out.println("["+APP+"] "+name+". Field \""+field+"\" has no positions indexed for cooc");
        continue;
      }
      
      System.out.println("["+APP+"] "+name+". Cooc indexation for field: "+field);
      alix.fieldRail(field);
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
    int exitCode = new CommandLine(new Load()).execute(args);
    System.exit(exitCode);
  }


}
