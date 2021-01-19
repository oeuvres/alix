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
package alix.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.analysis.FrDics;

/**
 * An empty class populating a possible list of bases in a WEB-INF folder.
 * Static load is efficient but not a lot talkative.
 * 
 * @author fred
 *
 */
public class Webinf
{
  static final String INDEXDIR = "indexdir";
  static public boolean bases = false;;
  static public void bases() throws IOException
  {
    // load properties for bases in WEB-INF/*.xml on webapp restart
    File zejar = new File(FrDics.class.getProtectionDomain().getCodeSource().getLocation().getPath());
    File webinf = zejar.getParentFile().getParentFile(); // alix.jar is supposed to be in WEB-INF/lib/ 
    for(File file: webinf.listFiles()) {
      String fileName = file.getName();
      if (fileName.startsWith("_")) continue; // easy way to unplug a base
      int pos = fileName.lastIndexOf(".");
      if (pos < 1) continue; // not /.fileName or /fileName
      String ext = fileName.substring(pos); // (with dot)
      if (!".xml".equals(ext)) continue;
      String name = fileName.substring(0, pos);
      if ("web".equals(name)) continue;
      Properties props = new Properties();
      System.out.println("[Alix] static LOAD a base properties: " + file);
      props.loadFromXML(new FileInputStream(file));
      String dic = props.getProperty("dicfile");
      if (dic != null) {
        File dicFile = new File(dic);
        if (!dicFile.isAbsolute()) dicFile = new File(file.getParentFile(), dic);
        if (dicFile.exists()) FrDics.load(dicFile);
      }
      if (!props.containsKey("label")) props.put("label", name);
      // test if lucene index exists and is OK
      String dstdir = props.getProperty("dstdir");
      if (dstdir == null) dstdir = webinf + "/bases/";
      File basesDir = new File(dstdir);
      if (!basesDir.isAbsolute()) basesDir = new File(file.getParentFile(), dstdir);
      if (!basesDir.isDirectory()) throw new FileNotFoundException("FilePath for bases is not a directory: " + basesDir);
      File indexDir = new File(basesDir, name);
      Alix alix = Alix.instance(name, indexDir.toPath(), new FrAnalyzer(), null);
      props.put(INDEXDIR, indexDir.toString());
      alix.props.putAll(props);
    }
    bases = true;
  }

}
