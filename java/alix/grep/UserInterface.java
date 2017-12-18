package alix.grep;

import java.awt.Insets;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Pattern;

import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

/**
 * Classe d'interface utilisation (les demandes sont faites et les queries
 * enregistrées) Les informations de base d'entrée tsv de l'utilisateur sont
 * enregistrées dans cette classe
 * 
 * @author user
 *
 */

public class UserInterface
{

  public static final String DEFAULT_PATH = "/home/odysseus/Téléchargements/critique2000-gh-pages/txt/";
  public static final String DEFAULT_TSV = "/home/odysseus/Téléchargements/critique2000-gh-pages/biblio3.tsv";
  public String query;
  String nameYearTitle;
  int caseSensitivity;
  HashMap<String, String[]> statsPerTitle;
  HashMap<String, String[]> statsPerAuthor;
  HashMap<String, String[]> statsPerYear;
  String form;
  int limit;

  public static final int colCode = 2;
  public static final int colAuthor = 3;
  public static final int colYear = 4;
  static final int colTitle = 5;
  String usersWord;

  public String getQuery()
  {
    return query;
  }

  public String getNameOrYearOrTitleString()
  {
    return nameYearTitle;
  }

  public int getCaseSensitivity()
  {
    return caseSensitivity;
  }

  public HashMap<String, String[]> getStatsPerDoc()
  {
    return statsPerTitle;
  }

  public HashMap<String, String[]> getStatsAuthor()
  {
    return statsPerAuthor;
  }

  public HashMap<String, String[]> getStatsYear()
  {
    return statsPerYear;
  }

  public String getFormPreference()
  {
    return form;
  }

  @SuppressWarnings("resource")
  public static void main(String[] args)
      throws MalformedURLException, SAXException, IOException, ParserConfigurationException
  {
    String infoTags = new String(Files.readAllBytes(Paths.get("./doc/tagsTable.txt")), StandardCharsets.UTF_8);
    UserInterface grep = new UserInterface();
    Scanner line = new Scanner(System.in);
    Scanner word = new Scanner(System.in);

    String doItAgain = "";
    String chosenPath = "";
    List<String[]> allRows = new ArrayList<String[]>();

    System.out.println("Définissez le chemin de votre fichier tsv (./Source/critique2000.tsv)");

    String tsvPath = line.nextLine();

    if (tsvPath.equals(null) || tsvPath.equals(""))
      tsvPath = DEFAULT_TSV;

    BufferedReader TSVFile = new BufferedReader(new FileReader(tsvPath));
    String dataRow = TSVFile.readLine();

    while (dataRow != null) {
      String[] dataArray = dataRow.split("\t");
      allRows.add(dataArray);
      dataRow = TSVFile.readLine();
    }

    TSVFile.close();

    System.out.println("Définissez le chemin vers vos fichiers à analyser "
        + "(exemple : /home/bilbo/Téléchargements/critique2000-gh-pages/txt/)");
    chosenPath = line.nextLine();

    if (chosenPath.equals(null) || chosenPath.equals(""))
      chosenPath = DEFAULT_PATH;

    while (!doItAgain.equals("n")) {
      grep.statsPerTitle = new HashMap<String, String[]>();

      JFrame pane = new JFrame("TAGS");
      JTextArea mytext = new JTextArea(infoTags);
      mytext.setMargin(new Insets(10, 10, 10, 10));
      pane.add(mytext);
      pane.pack();
      pane.setVisible(true);

      System.out.println("Quelle type de recherche voulez-vous effectuer ? "
          + "(rentrer le numéro correspondant et taper \"entrée\")");

      System.out.println("1 : rechercher un seul mot ou une expression régulière"
          + "\n(exemple : \"littérature\" ou \"littér(.)*\\s\"");

      System.out.println("2 : rechercher une liste de mots dans une fenêtre à définir"
          + "\n(exemple : \"littérature poésie art\" (à séparer par des espaces)");

      System.out.println("3 : rechercher un mot et au moins un tag"
          + "\n(exemple : \"littérature VERB DETart\" (à séparer par des espaces)");

      System.out.println(
          "4 : faire une recherche globale et individuelle sur les " + "patterns les plus fréquents autour d'un mot"
              + "\n(exemple : \"littérature VERB DETart\" (à séparer par des espaces)"
              + "\nLa recherche aboutira à un csv avec les 10 patterns les plus utilisés"
              + " sur tout le corpus, et leur utilisation pour chaque date ou auteur");
      int chooseTypeRequest = Integer.valueOf(word.next());

      System.out.println("Souhaitez-vous une recherche sur les lemmes ou sur les formes ? (l/f)");
      grep.form = word.next();

      Queries wordLookUp = new Queries();
      wordLookUp.setCaseSensitivity(grep.caseSensitivity);
      wordLookUp.setStatsPerTitle(new HashMap<>());
      wordLookUp.setStatsPerAuthor(new HashMap<>());
      wordLookUp.setStatsPerYear(new HashMap<>());
      wordLookUp.setFormPreference(grep.form);
      String casse = "";
      System.out.println("Votre requête doit-elle être sensible à la casse ? (o/n)");
      casse = word.next();

      if (casse.contains("o")) {
        grep.caseSensitivity = 0;
      }
      else {
        grep.caseSensitivity = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
      }

      switch (chooseTypeRequest) {
      case 1:
        wordLookUp.oneWord(chosenPath, allRows);
        grep.statsPerAuthor = wordLookUp.statsPerAuthor;
        grep.statsPerYear = wordLookUp.statsPerYear;
        grep.statsPerTitle = wordLookUp.getStatsTitle();
        grep.query = wordLookUp.getQuery();
        break;

      case 2:
        wordLookUp.severalWords(chosenPath, allRows);
        grep.statsPerAuthor = wordLookUp.statsPerAuthor;
        grep.statsPerYear = wordLookUp.statsPerYear;
        grep.statsPerTitle = wordLookUp.getStatsTitle();
        grep.query = wordLookUp.getQuery();
        break;

      case 3:
        wordLookUp.wordAndTags(chosenPath, allRows);
        grep.statsPerAuthor = wordLookUp.statsPerAuthor;
        grep.statsPerYear = wordLookUp.statsPerYear;
        grep.statsPerTitle = wordLookUp.getStatsTitle();
        grep.query = wordLookUp.getQuery();
        break;

      case 4:
        System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
        Scanner motsUtil = new Scanner(System.in);
        String queryUtil = motsUtil.nextLine();
        System.out.println("Quel est le nombre maximum d'occurrences du pattern que vous souhaitez chercher ?");
        Scanner nbUtil = new Scanner(System.in);
        grep.limit = Integer.parseInt(nbUtil.nextLine());
        wordLookUp.setLimit(grep.limit);
        wordLookUp.freqPatterns(tsvPath, chosenPath, queryUtil);

      }

      if (grep.statsPerAuthor != null && !grep.statsPerAuthor.isEmpty()) {

        String nomFichier = grep.query.replaceAll("\\\\", "") + "_" + grep.form;
        nomFichier = nomFichier.replaceAll("\\s", "_");
        String pathToSave = tsvPath.substring(0, tsvPath.lastIndexOf("/") + 1);

        ExportData.exportToCSV(pathToSave, nomFichier, grep.statsPerAuthor, grep.statsPerYear, grep.statsPerTitle);
        System.out.println("Votre requête a été sauvegardée");

      }

      System.out.println("\nVoulez-vous faire une nouvelle requête ? (o/n)");
      doItAgain = word.next();
    }
    System.out.println("Fin du programme");
    System.exit(0);
  }

}
