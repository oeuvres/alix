/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
/**
 * Static tools to deal with directories (and  files). Kept in java 7.
 */
public class Dir
{

  /**7
   * Delete a folder by path
   * (use java.nio stream, should be faster then {@link #rm(File)})
   */
  public static boolean rm(Path path) throws IOException {
    if (!Files.exists(path)) return false;
    if (Files.isDirectory(path)) {
      DirectoryStream<Path> stream = Files.newDirectoryStream(path);
      for (Path entry : stream) rm(entry);
    }
    Files.delete(path);
    return true;
  }

  /**
   * Delete a folder by File object
   * (maybe a bit slow for lots of big folders)
   */
  public static boolean rm(File file) {
    File[] ls = file.listFiles();
    if (ls != null) {
      for (File f : ls) {
        rm(f);
      }
    }
    return file.delete();
  }
  /**
   * Collect files recursively from a folder. Default regex pattern to select
   * files is : .*\.xml A regex selector can be provided by the path argument.
   * path= "folder/to/index/.*\.tei" Be careful, pattern is a regex, not a glob
   * (don not forger the dot for any character).
   * 
   * @param path
   * @return
   * @throws FileNotFoundException 
   */
  public static List<File> ls(String path) throws FileNotFoundException
  {
    ArrayList<File> files = new ArrayList<File>();
    return ls(path, files);
  }

  public static List<File> ls(String path, List<File> files) throws FileNotFoundException
  {
    if (files == null) files = new ArrayList<File>();
    File dir = new File(path);
    String re = ".*\\.xml";
    if (!dir.isDirectory()) {
      re = dir.getName();
      dir = dir.getParentFile();
      if (!dir.isDirectory()) {
        throw new FileNotFoundException(path + " does not exists.");
      }
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
