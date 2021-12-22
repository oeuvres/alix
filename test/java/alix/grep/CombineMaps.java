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

import java.util.HashMap;
import java.util.Map.Entry;

/**
 * Classe de méthodes qui combinent les nouvelles données des
 * auteurs/dates/titres aux données déjà existantes Les méthodes de la classe
 * ont besoin de la map de stats auteur/date déjà existante et de la liste des
 * stats par titre Après utilisation, la map des données statistiques pour
 * chaque auteur est mise à jour, sous forme de map combinée
 * 
 * @author user
 *
 */

public class CombineMaps
{

  public static final int colCode = 2;
  public static final int colAuthor = 3;
  public static final int colYear = 4;
  static final int colTitle = 5;

  HashMap<String, String[]> statsPerTitle;
  HashMap<String, String[]> statsPerAuthor;
  HashMap<String, String[]> statsPerYear;

  public void setStatsPerTitle(HashMap<String, String[]> statsPerDoc)
  {
    this.statsPerTitle = statsPerDoc;
  }

  public HashMap<String, String[]> getStatsPerDoc()
  {
    return statsPerTitle;
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

  public HashMap<String, int[]> combine(HashMap<String, String[]> statsPerAuthorOrYear, int valueAsked)
  {
    HashMap<String, int[]> combinedStats = new HashMap<String, int[]>();
    for (Entry<String, String[]> entry : statsPerAuthorOrYear.entrySet()) {
      String keyStr = entry.getValue()[valueAsked];
      if (combinedStats.isEmpty() == false && combinedStats.containsKey(keyStr)) {
        int[] stats = new int[2];
        int previousTotalInt = Integer.parseInt(entry.getValue()[1]);
        int previousNbMatches = Integer.parseInt(entry.getValue()[2]);
        stats[0] = previousTotalInt + combinedStats.get(keyStr)[0];
        stats[1] = previousNbMatches + combinedStats.get(keyStr)[1];
        combinedStats.put(entry.getValue()[valueAsked], stats);
      }
      else {
        int[] stats = new int[2];
        stats[0] = Integer.parseInt(entry.getValue()[1]);
        stats[1] = Integer.parseInt(entry.getValue()[2]);
        combinedStats.put(keyStr, stats);
      }
    }

    return combinedStats;

  }

  public void mergeData(String cells[], int countOccurrences, long occs, String fileName)
  {
    String statsPourListeDocs[] = new String[7];
    String[] tmp;

    // List<String[]>statsPerDoc=grep.getStatsPerDoc();

    if (statsPerAuthor.containsKey(cells[UserInterface.colAuthor])) {
      tmp = new String[7];
      tmp[1] = String
          .valueOf(Integer.parseInt(statsPerAuthor.get(cells[UserInterface.colAuthor])[1]) + countOccurrences);
      tmp[2] = String.valueOf(Integer.parseInt(statsPerAuthor.get(cells[UserInterface.colAuthor])[2]) + occs);
      tmp[0] = String.valueOf(
          ((Double.parseDouble(statsPerAuthor.get(cells[UserInterface.colAuthor])[1]) + countOccurrences) * 1000000)
              / (Integer.parseInt(statsPerAuthor.get(cells[UserInterface.colAuthor])[2]) + occs)); // Relative Frequency
      tmp[3] = cells[UserInterface.colAuthor]; // Authors name
      tmp[4] = statsPerAuthor.get(cells[UserInterface.colAuthor])[4] + " // " + cells[UserInterface.colYear];
      tmp[5] = statsPerAuthor.get(cells[UserInterface.colAuthor])[5] + " // " + cells[UserInterface.colTitle]; // Title
      tmp[6] = fileName;
    }
    else {
      tmp = new String[7];
      tmp[1] = String.valueOf(countOccurrences);
      tmp[2] = String.valueOf(occs);
      tmp[0] = String.valueOf((countOccurrences * 1000000) / occs); // Relative Frequency
      tmp[3] = cells[UserInterface.colAuthor]; // Authors name
      tmp[4] = cells[UserInterface.colYear]; // Year
      tmp[5] = cells[UserInterface.colTitle]; // Title
      tmp[6] = fileName;

    }
    statsPourListeDocs[1] = String.valueOf(countOccurrences);
    statsPourListeDocs[2] = String.valueOf(occs);
    statsPourListeDocs[0] = String.valueOf((countOccurrences * 1000000) / occs); // Relative Frequency
    statsPourListeDocs[3] = cells[UserInterface.colAuthor]; // Authors name
    statsPourListeDocs[4] = cells[UserInterface.colYear]; // Year
    statsPourListeDocs[5] = cells[UserInterface.colTitle]; // Title
    statsPourListeDocs[6] = fileName;
    statsPerTitle.put(statsPourListeDocs[5], statsPourListeDocs);
    statsPerAuthor.put(cells[colAuthor], tmp);

    if (statsPerYear.containsKey(cells[UserInterface.colYear])) {
      tmp = new String[7];
      tmp[1] = String.valueOf(Integer.parseInt(statsPerYear.get(cells[UserInterface.colYear])[1]) + countOccurrences);
      tmp[2] = String.valueOf(Integer.parseInt(statsPerYear.get(cells[UserInterface.colYear])[2]) + occs);
      tmp[0] = String.valueOf(
          ((Double.parseDouble(statsPerYear.get(cells[UserInterface.colYear])[1]) + countOccurrences) * 1000000)
              / (Integer.parseInt(statsPerYear.get(cells[UserInterface.colYear])[2]) + occs)); // Relative Frequency
      tmp[3] = statsPerYear.get(cells[UserInterface.colYear])[3] + " // " + cells[UserInterface.colAuthor]; // Authors
                                                                                                            // name
      tmp[4] = cells[UserInterface.colYear]; // Year
      tmp[5] = statsPerYear.get(cells[UserInterface.colYear])[5] + " // " + cells[UserInterface.colTitle]; // Title
      tmp[6] = fileName;
    }
    else {
      tmp = new String[7];
      tmp[1] = String.valueOf(countOccurrences);
      tmp[2] = String.valueOf(occs);
      tmp[0] = String.valueOf((countOccurrences * 1000000) / occs); // Relative Frequency
      tmp[3] = cells[UserInterface.colAuthor]; // Authors name
      tmp[4] = cells[UserInterface.colYear]; // Year
      tmp[5] = cells[UserInterface.colTitle]; // Title
      tmp[6] = fileName;

    }
    statsPourListeDocs[1] = String.valueOf(countOccurrences);
    statsPourListeDocs[2] = String.valueOf(occs);
    statsPourListeDocs[0] = String.valueOf((countOccurrences * 1000000) / occs); // Relative Frequency
    statsPourListeDocs[3] = cells[UserInterface.colAuthor]; // Authors name
    statsPourListeDocs[4] = cells[UserInterface.colYear]; // Year
    statsPourListeDocs[5] = cells[UserInterface.colTitle]; // Title
    statsPourListeDocs[6] = fileName;
    statsPerTitle.put(statsPourListeDocs[5], statsPourListeDocs);
    statsPerYear.put(cells[UserInterface.colYear], tmp);

    // statsPourListeDocs[1]=String.valueOf(countOccurrences);
    // statsPourListeDocs[2]=String.valueOf( freqs );
    // statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ freqs );
    // //Relative Frequency
    // statsPourListeDocs[3]=cells[UserInterface.colAuthor]; //Authors name
    // statsPourListeDocs[4]=cells[UserInterface.colYear]; // Year
    // statsPourListeDocs[5]=cells[UserInterface.colTitle]; // Title
    // statsPourListeDocs[6]=fileName;
    // statsPerDoc.add(statsPourListeDocs);
  }
}
