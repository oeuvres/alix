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
package alix.grep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import alix.fr.Tokenizer;
import alix.grep.query.Query;
import alix.util.Chain;
import alix.util.Occ;

/**
 * La classe contient les méthodes qui ramassent les fichiers, appellent le
 * tokenizer et appellent le classe de compilation des données Les méthodes de
 * la classe ont besoin des demandes de l'utilisateur, et modifient au fur et à
 * mesure la map des données pour chaque auteur Après utilisation, la map des
 * données statistiques pour chaque auteur est mise à jour
 * 
 * @author user
 *
 */

public class Queries
{

  String preciseQuery;
  String query;
  int caseSensitivity;
  String nameYearTitle;
  HashMap<String, String[]> statsPerAuthor;
  HashMap<String, String[]> statsPerYear;
  HashMap<String, String[]> statsPerTitle;
  String form;
  static int limit;

  public static final int colCode = 2;
  public static final int colAuthor = 3;
  public static final int colYear = 4;
  static final int colTitle = 5;

  // List<String[]>statsPerDoc;

  public String getPreciseQuery()
  {
    return preciseQuery;
  }

  public void setPreciseQuery(String query)
  {
    this.preciseQuery = query;
  }

  public String getQuery()
  {
    return query;
  }

  // public void setStatsPerDoc(List<String[]>statsPerDoc){
  // this.statsPerDoc=statsPerDoc;
  // }
  //
  // public List<String[]> getStatsPerDoc(){
  // return statsPerDoc;
  // }

  public int getCaseSensitivity()
  {
    return caseSensitivity;
  }

  public void setCaseSensitivity(int query)
  {
    this.caseSensitivity = query;
  }

  public HashMap<String, String[]> getStatsAuthor()
  {
    return statsPerAuthor;
  }

  public void setStatsPerAuthor(HashMap<String, String[]> stats)
  {
    this.statsPerAuthor = stats;
  }

  public HashMap<String, String[]> getStatsYear()
  {
    return statsPerYear;
  }

  public void setStatsPerYear(HashMap<String, String[]> stats)
  {
    this.statsPerYear = stats;
  }

  public HashMap<String, String[]> getStatsTitle()
  {
    return statsPerTitle;
  }

  public void setStatsPerTitle(HashMap<String, String[]> stats)
  {
    this.statsPerTitle = stats;
  }

  public String getFormPreference()
  {
    return form;
  }

  public void setFormPreference(String form)
  {
    this.form = form;
  }

  public int getLimit()
  {
    return limit;
  }

  public void setLimit(int limit)
  {
    Queries.limit = limit;
  }

  @SuppressWarnings("resource")
  public void oneWord(String chosenPath, List<String[]> allRows) throws IOException
  {
    System.out.println("Quel mot voulez-vous chercher ?");

    Scanner answer = new Scanner(System.in);
    query = answer.nextLine();

    System.out.println("Calcul des matchs en cours...");

    for (int counterRows = 1; counterRows < allRows.size(); counterRows++) {
      String[] cells = allRows.get(counterRows);
      int countOccurrences = 0;
      String fileName = cells[UserInterface.colCode] + ".xml";
      StringBuilder pathSB = new StringBuilder();
      pathSB.append(chosenPath);
      pathSB.append(fileName);
      Path path = Paths.get(pathSB.toString());
      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      Tokenizer toks = new Tokenizer(text);
      long occs = 0;
      Occ occ = new Occ();

      while (toks.token(occ)) {
        if (occ.tag().isPun())
          continue;
        Chain chain = null;
        if (form.contains("l")) {
          chain = occ.lem();
        }
        else {
          chain = occ.graph();
        }
        occs++;

        Pattern p = Pattern.compile(query, caseSensitivity);
        Matcher m = p.matcher(chain.toString());

        if (m.matches()) {
          countOccurrences++;
        }
      }

      CombineMaps combine = new CombineMaps();
      combine.setStatsPerTitle(getStatsTitle());
      combine.setStatsPerAuthor(getStatsAuthor());
      combine.setStatsPerYear(getStatsYear());
      combine.mergeData(cells, countOccurrences, occs, fileName);
      statsPerAuthor = combine.statsPerAuthor;
      statsPerYear = combine.statsPerYear;
      statsPerTitle = combine.getStatsPerDoc();
    }
    System.out.println("Fin des calculs");
  }

  @SuppressWarnings("resource")
  public void severalWords(String chosenPath, List<String[]> allRows) throws IOException
  {
    System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
    Scanner motsUtil = new Scanner(System.in);
    query = motsUtil.nextLine();
    HashMap<String, WordFlag> listToCheck = new HashMap<String, WordFlag>();
    String tabDeMots[] = query.split("\\s");
    for (String mot : tabDeMots) {
      listToCheck.put(mot, new WordFlag());
    }

    int window = 0;
    if (listToCheck.keySet().size() > 1) {
      System.out.println("Quelle est l'étendue de votre fenêtre (en nombre de mots) ?");
      Scanner answerWord = new Scanner(System.in);
      window = Integer.valueOf(answerWord.next());
    }

    System.out.println("Calcul des matchs en cours...");

    for (int counterRows = 1; counterRows < allRows.size(); counterRows++) {
      String[] cells = allRows.get(counterRows);

      int countOccurrences = 0;
      String fileName = cells[UserInterface.colCode] + ".xml";
      StringBuilder pathSB = new StringBuilder();
      pathSB.append(chosenPath);
      pathSB.append(fileName);
      Path path = Paths.get(pathSB.toString());
      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      Tokenizer toks = new Tokenizer(text);
      Occ occ = new Occ();
      long occs = 0;

      int innerWin = -1;
      while (toks.token(occ)) {
        if (occ.tag().isPun())
          continue;
        occs++;
        Chain chain = null;
        if (form.contains("l")) {
          chain = occ.lem();
        }
        else {
          chain = occ.graph();
        }
        @SuppressWarnings("unlikely-arg-type")
        WordFlag test = listToCheck.get(chain);

        if (test != null) {
          test.value = true;
          if (innerWin < 0)
            innerWin = 0;
        }

        if (innerWin == window) {
          int nbTrue = 0;
          for (Entry<String, WordFlag> entry : listToCheck.entrySet()) {
            if (entry.getValue().value == true) {
              nbTrue++;
              entry.getValue().value = false;
            }
          }
          if (nbTrue == listToCheck.keySet().size()) {
            countOccurrences++;
            innerWin = -1;
          }
        }

        if (innerWin > -1) {
          innerWin++;
        }
      }

      CombineMaps combine = new CombineMaps();
      combine.setStatsPerTitle(getStatsTitle());
      combine.setStatsPerAuthor(getStatsAuthor());
      combine.setStatsPerYear(getStatsYear());
      combine.mergeData(cells, countOccurrences, occs, fileName);
      statsPerAuthor = combine.statsPerAuthor;
      statsPerYear = combine.statsPerYear;
      statsPerTitle = combine.statsPerTitle;
    }
  }

  public class WordFlag
  {
    public boolean value = false;
  }

  @SuppressWarnings("resource")
  public void wordAndTags(String chosenPath, List<String[]> allRows) throws IOException
  {

    System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
    Scanner motsUtil = new Scanner(System.in);
    query = motsUtil.nextLine();

    System.out.println("Calcul des matchs en cours...");

    for (int counterRows = 1; counterRows < allRows.size(); counterRows++) {
      String[] cells = allRows.get(counterRows);
      int occurrences = 0;
      int countFound = 0;
      String fileName = cells[UserInterface.colCode] + ".xml";
      StringBuilder pathSB = new StringBuilder();
      pathSB.append(chosenPath);
      pathSB.append(fileName);
      Path path = Paths.get(pathSB.toString());

      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      Tokenizer toks = new Tokenizer(text);
      Query q1 = new Query(query);
      Occ occ = new Occ();
      List<String> nbFound = new ArrayList<String>();
      while (toks.token(occ)) {
        if (q1.test(occ)) {
          StringBuilder sb = new StringBuilder();

          for (int indexOcc = 0; indexOcc < q1.found().size(); indexOcc++) {
            sb.append(q1.found().get(indexOcc).lem().toString().toLowerCase() + " ");
          }

          nbFound.add(sb.toString());
        }
        occurrences++;
      }

      countFound += nbFound.size();

      CombineMaps combine = new CombineMaps();
      combine.setStatsPerTitle(getStatsTitle());
      combine.setStatsPerAuthor(getStatsAuthor());
      combine.setStatsPerYear(getStatsYear());
      combine.mergeData(cells, countFound, occurrences, fileName);
      statsPerAuthor = combine.statsPerAuthor;
      statsPerYear = combine.statsPerYear;
      statsPerTitle = combine.getStatsPerDoc();
    }
  }

  /**
   * 
   * @param pathTSV
   *          to source tsv
   * @param pathCorpus
   *          to source corpus
   * @param queries
   */
  @SuppressWarnings("unlikely-arg-type")
  public void freqPatterns(String pathTSV, String pathCorpus, String queries) throws IOException
  {

    BufferedReader TSVFile = new BufferedReader(new FileReader(pathTSV));
    List<String> globalResults = new ArrayList<String>();
    LinkedHashMap<String, Integer> orderedGlobalResults = new LinkedHashMap<String, Integer>();
    File directory = new File(pathCorpus);

    File[] alltexts = directory.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name)
      {
        return name.toLowerCase().endsWith(".xml");
      }
    });
    System.out.println("Calcul des matchs en cours...");
    float numberOccs = 0;
    for (File file : alltexts) {
      Query q1 = new Query(queries);
      String xmlTest = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      Tokenizer toks = new Tokenizer(xmlTest);
      Occ occ = new Occ();

      while (toks.token(occ)) {
        if (q1.test(occ)) {
          StringBuilder sb = new StringBuilder();
          if (occ.tag().isName()) {
            for (int indexOcc = 0; indexOcc < q1.foundSize(); indexOcc++) {
              sb.append(q1.found().get(indexOcc).lem().toString() + " ");
            }
          }
          else {
            for (int indexOcc = 0; indexOcc < q1.found().size(); indexOcc++) {
              sb.append(q1.found().get(indexOcc).lem().toString().toLowerCase() + " ");
            }
          }
          globalResults.add(sb.toString());
        }
        numberOccs++;
      }
    }

    Set<String> uniqueSet = new HashSet<String>(globalResults);
    for (String temp : uniqueSet) {
      orderedGlobalResults.put(temp, Collections.frequency(globalResults, temp));
    }

    System.out.println("Fin des calculs globaux, début des calculs individuels");

    orderedGlobalResults = sortMyMapByValue(orderedGlobalResults);
    String queryForFile = queries.replaceAll("\\W+", "_");
    String saveFolder = new File(pathTSV).getParentFile().getAbsolutePath() + "/";

    String dataRow = TSVFile.readLine();
    List<String[]> allRows = new ArrayList<String[]>();
    String[] dataArray = null;

    while (dataRow != null) {
      dataArray = dataRow.split("\t");
      allRows.add(dataArray);
      dataRow = TSVFile.readLine();
    }
    TSVFile.close();

    HashMap<String[], LinkedHashMap<String, Integer>> mapTitle = new HashMap<String[], LinkedHashMap<String, Integer>>();
    for (int counterRows = 1; counterRows < allRows.size(); counterRows++) {
      List<String> indivResults = new ArrayList<String>();
      String[] cells = allRows.get(counterRows);
      String fileName = cells[UserInterface.colCode] + ".xml";
      String query[] = cells;
      String queryName = cells[UserInterface.colTitle];

      Query q1 = new Query(queries);
      Path path = Paths.get(pathCorpus + "/" + fileName);
      if (Files.exists(path)) {
        String xml = new String(Files.readAllBytes(Paths.get(pathCorpus + fileName)), StandardCharsets.UTF_8);
        Tokenizer toks = new Tokenizer(xml);
        Occ occ = new Occ();

        while (toks.token(occ)) {
          if (q1.test(occ)) {
            StringBuilder sb = new StringBuilder();
            if (occ.tag().toString().contains("NAME")) {

              for (int indexOcc = 0; indexOcc < q1.foundSize(); indexOcc++) {
                sb.append(q1.found().get(indexOcc).lem().toString() + " ");
              }
            }
            else {
              for (int indexOcc = 0; indexOcc < q1.foundSize(); indexOcc++) {
                sb.append(q1.found().get(indexOcc).lem().toString().toLowerCase() + " ");
              }
            }
            indivResults.add(sb.toString());
          }
        }

        LinkedHashMap<String, Integer> findings = new LinkedHashMap<String, Integer>();
        if (mapTitle.containsKey(queryName)) {
          findings = mapTitle.get(queryName);
          for (String key : orderedGlobalResults.keySet()) {
            if (!findings.containsKey(key)) {
              findings.put(key, Collections.frequency(indivResults, key));
            }
            else {
              int previous = findings.get(key);
              findings.put(key, Collections.frequency(indivResults, key) + previous);
            }
          }
        }
        else {
          for (String key : orderedGlobalResults.keySet()) {
            if (!findings.containsKey(key)) {
              findings.put(key, Collections.frequency(indivResults, key));
            }
            else {
              int previous = findings.get(key);
              findings.put(key, Collections.frequency(indivResults, key) + previous);
            }
          }
        }
        mapTitle.put(query, findings);
      }
    }
    System.out.println("Fin des calculs");

    // HashMap<String, Float>countAuthor=countTokens(pathCorpus, colAuthor,
    // allRows);
    // HashMap<String, Float>countDate=countTokens(pathCorpus, colYear, allRows);
    HashMap<String, Float> countTitle = countTokens(pathCorpus, colTitle, allRows);

    ExportData.doubleMapExport(saveFolder, queryForFile, numberOccs, mapTitle, orderedGlobalResults, countTitle);
  }

  public static LinkedHashMap<String, Integer> sortMyMapByValue(LinkedHashMap<String, Integer> map)
  {
    /* Java8, not supported on old Debian
    LinkedHashMap<String, Integer> sortedMap = map.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit)
        .collect(Collectors.toMap(Entry::getKey, Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
      */
    return null;
  }

  public HashMap<String, Float> countTokens(String chosenPath, int chosenColumn, List<String[]> allRows)
      throws IOException
  {
    HashMap<String, Float> map = new HashMap<String, Float>();

    for (int counterRows = 1; counterRows < allRows.size(); counterRows++) {
      String[] cells = allRows.get(counterRows);
      float indivNbTokens = 0;
      String fileName = cells[UserInterface.colCode] + ".xml";
      StringBuilder pathSB = new StringBuilder();
      pathSB.append(chosenPath);
      pathSB.append(fileName);
      Path path = Paths.get(pathSB.toString());
      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      Tokenizer toks = new Tokenizer(text);
      Occ occ = new Occ();

      while (toks.token(occ)) {
        indivNbTokens++;
      }

      if (map.containsKey(cells[chosenColumn])) {
        float previous = map.get(cells[chosenColumn]);
        map.put(cells[chosenColumn], previous + indivNbTokens);
      }
      else {
        map.put(cells[chosenColumn], indivNbTokens);
      }
    }

    return map;
  }
}
