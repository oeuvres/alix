package alix.grep;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

public class ExportData
{

  public static void exportToCSV(String folderPath, String fileName, HashMap<String, String[]> statsAuthor,
      HashMap<String, String[]> statsYear, HashMap<String, String[]> statsTitle)

  {
    try {
      Path path = Paths.get(folderPath);

      File file = new File(folderPath + fileName + "_author.tsv");
      if (!file.getParentFile().isDirectory()) {
        Files.createDirectories(path);
      }

      FileWriter writer = new FileWriter(file);

      writer.append("Auteur\t");
      writer.append("Total De Tokens\t");
      writer.append("Occurrences du Mot\t");
      writer.append("Fréquence Relative\t");
      writer.append("Date\t");
      writer.append("Titre\t");
      writer.append('\n');

      for (Entry<String, String[]> entry : statsAuthor.entrySet()) {
        writer.append(entry.getValue()[3] + "\t");
        writer.append(entry.getValue()[2] + "\t");
        writer.append(entry.getValue()[1] + "\t");
        writer.append(entry.getValue()[0] + "\t");
        writer.append(entry.getValue()[4] + "\t");
        writer.append(entry.getValue()[5] + "\t");
        writer.append('\n');
      }

      writer.flush();
      writer.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    try {
      Path path = Paths.get(folderPath);

      File file = new File(folderPath + fileName + "_date.tsv");
      if (!file.getParentFile().isDirectory()) {
        Files.createDirectories(path);
      }

      FileWriter writer = new FileWriter(file);

      writer.append("Date\t");
      writer.append("Total De Tokens\t");
      writer.append("Occurrences du Mot\t");
      writer.append("Fréquence Relative\t");
      writer.append("Auteur\t");
      writer.append("Titre\t");
      writer.append('\n');

      for (Entry<String, String[]> entry : statsYear.entrySet()) {
        writer.append(entry.getValue()[4] + "\t");
        writer.append(entry.getValue()[2] + "\t");
        writer.append(entry.getValue()[1] + "\t");
        writer.append(entry.getValue()[0] + "\t");
        writer.append(entry.getValue()[3] + "\t");
        writer.append(entry.getValue()[5] + "\t");
        writer.append('\n');
      }

      writer.flush();
      writer.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    try {
      Path path = Paths.get(folderPath);

      File file = new File(folderPath + fileName + "_title.tsv");
      if (!file.getParentFile().isDirectory()) {
        Files.createDirectories(path);
      }

      FileWriter writer = new FileWriter(file);

      writer.append("Titre\t");
      writer.append("Total De Tokens\t");
      writer.append("Occurrences du Mot\t");
      writer.append("Fréquence Relative\t");
      writer.append("Date\t");
      writer.append("Auteur\t");
      writer.append('\n');

      for (Entry<String, String[]> entry : statsTitle.entrySet()) {
        writer.append(entry.getValue()[5] + "\t");
        writer.append(entry.getValue()[2] + "\t");
        writer.append(entry.getValue()[1] + "\t");
        writer.append(entry.getValue()[0] + "\t");
        writer.append(entry.getValue()[4] + "\t");
        writer.append(entry.getValue()[3] + "\t");
        writer.append('\n');
      }

      writer.flush();
      writer.close();
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void doubleMapExport(String saveFolder, String queryForFile, float numberOccs,
      HashMap<String[], LinkedHashMap<String, Integer>> mapAuthor, LinkedHashMap<String, Integer> orderedGlobalResults,
      // HashMap<String, Float>countAuthor,
      // HashMap<String, Float>countDate,
      HashMap<String, Float> countTitle) throws IOException
  {

    File fileGlobal = new File(saveFolder + queryForFile + "_globalPatterns.tsv");
    FileWriter writerGlobal = new FileWriter(fileGlobal, false);
    Path path1 = Paths.get(saveFolder);
    if (!fileGlobal.getParentFile().isDirectory()) {
      Files.createDirectories(path1);
    }

    writerGlobal.append("Pattern\t");
    writerGlobal.append("Nombre\t");
    writerGlobal.append("Frequence Relative\t");
    writerGlobal.append("TotalTokens");
    writerGlobal.append('\n');
    for (Entry<String, Integer> entry : orderedGlobalResults.entrySet()) {
      writerGlobal.append(entry.getKey() + "\t");
      writerGlobal.append(entry.getValue() + "\t");
      writerGlobal.append((float) entry.getValue() * 1000000 / numberOccs + "\t");
      writerGlobal.append(numberOccs + "");
      writerGlobal.append('\n');
    }
    writerGlobal.flush();
    writerGlobal.close();

    File fileTSV = new File(saveFolder + queryForFile + "_indivPatterns.tsv");
    FileWriter writer = new FileWriter(fileTSV, false);
    if (!fileTSV.getParentFile().isDirectory()) {
      Files.createDirectories(path1);
    }

    writer.append("Nom \t");
    writer.append("Titre \t");
    writer.append("Date \t");
    writer.append("Pattern\t");
    writer.append("Nombre\t");
    writer.append("Frequence Relative\t");
    writer.append("TotalTokens\t");
    writer.append('\n');
    for (Entry<String[], LinkedHashMap<String, Integer>> entry : mapAuthor.entrySet()) {
      for (Entry<String, Integer> values : entry.getValue().entrySet()) {
        String value = values.getKey();
        long nb = values.getValue();
        writer.append(entry.getKey()[UserInterface.colAuthor] + "\t");
        writer.append(entry.getKey()[UserInterface.colTitle] + "\t");
        writer.append(entry.getKey()[UserInterface.colYear] + "\t");
        writer.append(value + "\t");
        writer.append(nb + "\t");
        if (countTitle.containsKey(entry.getKey()[UserInterface.colTitle])) {
          writer.append((float) nb * 1000000 / countTitle.get(entry.getKey()[UserInterface.colTitle]) + "\t");
          writer.append(countTitle.get(entry.getKey()[UserInterface.colTitle]) + "");
        }
        writer.append('\n');
      }
    }
    writer.flush();
    writer.close();
  }
}
