package alix.grep;

import java.awt.Insets;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Pattern;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import alix.fr.Occ;
import alix.util.SparseMat;
import alix.util.Term;

/**
 * Classe d'interface utilisation (les demandes sont faites et les queries enregistrées)
 * Les informations de base d'entrée tsv de l'utilisateur sont enregistrées dans cette classe
 * @author user
 *
 */

public class GrepMultiWordExpressions {

	public static final String DEFAULT_PATH="/home/odysseus/Téléchargements/critique2000-gh-pages/txt/";
	public static final String DEFAULT_TSV="/home/odysseus/Téléchargements/critique2000-gh-pages/biblio3.tsv";
	public String query;
	String nameYearTitle;
	int caseSensitivity;
	List<String[]>statsPerDoc;
	HashMap <String,String[]>statsPerAuthorYear;
	String form;
	int limit;

	public static final int colCode=2;
	public static final int colAuthor=3;
	public static final int colYear=4;
	static final int colTitle=5;
	String usersWord;

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public String getNameOrYearOrTitleString() {
		return nameYearTitle;
	}

	public void setNameOrYearOrTitleString(String query) {
		this.nameYearTitle = query;
	}

	public int getCaseSensitivity() {
		return caseSensitivity;
	}

	public void setCaseSensitivity(int query) {
		this.caseSensitivity = query;
	}

	public List<String[]> getStatsPerDoc() {
		return statsPerDoc;
	}

	public void setStatsPerDoc(List<String[]>stats) {
		this.statsPerDoc = stats;
	}
	
	public HashMap<String, String[]> getStatsAuthorYear() {
		return statsPerAuthorYear;
	}

	public void setStatsPerAuthorYear(HashMap<String, String[]> stats) {
		this.statsPerAuthorYear = stats;
	}
	
	public String getFormPreference() {
		return form;
	}

	public void setFormPreference (String form) {
		this.form = form;
	}

	@SuppressWarnings("resource")
	public static void main(String[] args) throws MalformedURLException, SAXException, 
	IOException, ParserConfigurationException {
		String infoTags = new String(Files.readAllBytes( Paths.get( "./doc/tagsTable.txt") ), StandardCharsets.UTF_8);
		GrepMultiWordExpressions grep=new GrepMultiWordExpressions();
		Scanner line=new Scanner(System.in);
		Scanner word=new Scanner(System.in);
		
		String doItAgain="";
		String chosenPath="";
		String preciseQuery="";
		List <String []>allRows=new ArrayList<String[]>();

		System.out.println("Définissez le chemin de votre fichier tsv (./Source/critique2000.tsv)");
		
		String tsvPath=line.nextLine();
		
		if(tsvPath.equals(null)||tsvPath.equals(""))tsvPath=DEFAULT_TSV;

		BufferedReader TSVFile = new BufferedReader(new FileReader(tsvPath));

		String dataRow = TSVFile.readLine();

		while (dataRow != null){
			String[] dataArray = dataRow.split("\t");
			allRows.add(dataArray);
			dataRow = TSVFile.readLine();
		}

		TSVFile.close();

		System.out.println("Définissez le chemin vers vos fichiers à analyser "
				+ "(exemple : /home/bilbo/Téléchargements/critique2000-gh-pages/txt/)");
		chosenPath=line.nextLine();

		if(chosenPath.equals(null)||chosenPath.equals(""))chosenPath=DEFAULT_PATH;

		while (!doItAgain.equals("n")){
			grep.setStatsPerDoc(new ArrayList<String[]>());

			System.out.println("Souhaitez-vous un tsv regroupé par par nom, par date ou par titre ? "
					+ "(réponses : nom/date/titre) :");
			grep.nameYearTitle=word.next();

			int column=grep.rechercheParNomDateTitrePourTSV(grep.getNameOrYearOrTitleString());		
			int valueAsked=0;
			if (column==colYear)valueAsked=4;
			if (column==colAuthor)valueAsked=3;
			if (column==colTitle)valueAsked=5;

			JFrame pane=new JFrame("TAGS");
			JTextArea mytext=new JTextArea(infoTags);
			mytext.setMargin(new Insets(10,10,10,10));
			pane.add(mytext);
			pane.pack();
			pane.show();
			
			
			System.out.println("Quelle type de recherche voulez-vous effectuer ? "
					+ "(rentrer le numéro correspondant et taper \"entrée\")");
			System.out.println("1 : rechercher un seul mot ou une expression régulière"
					+ "\n(exemple : \"littérature\" ou \"littér(.)*\\s\"");
			System.out.println("2 : rechercher une liste de mots dans une fenêtre à définir"
					+ "\n(exemple : \"littérature poésie art\" (à séparer par des espaces)");
			System.out.println("3 : rechercher un mot et au moins un tag"
					+ "\n(exemple : \"littérature VERB DETart\" (à séparer par des espaces)");
			System.out.println("4 : faire une recherche globale et individuelle sur les "
					+ "patterns les plus fréquents autour d'un mot"
					+ "\n(exemple : \"littérature VERB DETart\" (à séparer par des espaces)"
					+ "\nLa recherche aboutira à un csv avec les 10 patterns les plus utilisés"
					+ "sur tout le corpus, et leur utilisation pour chaque date ou auteur");
			int chooseTypeRequest = Integer.valueOf(word.next());

			
			

			WordLookUp wordLookUp=new WordLookUp();
			CombineStats combine=new CombineStats();
			wordLookUp.setCaseSensitivity(grep.caseSensitivity);
			wordLookUp.setNameYearTitle(grep.nameYearTitle);
			wordLookUp.setStatsPerDoc(new ArrayList<String[]>());
			wordLookUp.setStatsPerAuthorYear(new HashMap<>());
			wordLookUp.setFormPreference(grep.form);
			String casse="";

			switch (chooseTypeRequest){
			case 1 :
				System.out.println("Souhaitez-vous une recherche sur les lemmes ou sur les formes ? (l/f)");
				grep.form=word.next();
				System.out.println("Votre requête doit-elle être sensible à la casse ? (o/n)");
				casse=word.next();

				if (casse.contains("o")){
					grep.caseSensitivity=0;
				}
				else{
					grep.caseSensitivity=Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
				}
				grep.statsPerAuthorYear=wordLookUp.oneWord(chosenPath, column, allRows);
				preciseQuery=wordLookUp.getPreciseQuery();
				grep.statsPerDoc=wordLookUp.getStatsPerDoc();
				grep.query=wordLookUp.getQuery();
				break;

			case 2 :
				System.out.println("Souhaitez-vous une recherche sur les lemmes ou sur les formes ? (l/f)");
				grep.form=word.next();
				System.out.println("Votre requête doit-elle être sensible à la casse ? (o/n)");
				casse=word.next();

				if (casse.contains("o")){
					grep.caseSensitivity=0;
				}
				else{
					grep.caseSensitivity=Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
				}
				grep.statsPerAuthorYear=wordLookUp.severalWords(chosenPath, column, allRows);
				preciseQuery=wordLookUp.getPreciseQuery();
				grep.statsPerDoc=wordLookUp.getStatsPerDoc();
				grep.query=wordLookUp.getQuery();				
				break;
			
			case 3:
				System.out.println("Souhaitez-vous une recherche sur les lemmes ou sur les formes ? (l/f)");
				grep.form=word.next();
				System.out.println("Votre requête doit-elle être sensible à la casse ? (o/n)");
				casse=word.next();

				if (casse.contains("o")){
					grep.caseSensitivity=0;
				}
				else{
					grep.caseSensitivity=Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
				}
				grep.statsPerAuthorYear=wordLookUp.wordAndTags(chosenPath, column, allRows);
				preciseQuery=wordLookUp.getPreciseQuery();
				grep.statsPerDoc=wordLookUp.getStatsPerDoc();
				grep.query=wordLookUp.getQuery();
				break;	
				
			case 4:
				
//				JOptionPane.showMessageDialog(null, infoTags, "TAGS", JOptionPane.PLAIN_MESSAGE);
				System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
				Scanner motsUtil=new Scanner (System.in);
				String queryUtil = motsUtil.nextLine();
				System.out.println("Quel est le nombre maximum d'occurrences du pattern que vous souhaitez chercher ?");
				Scanner nbUtil=new Scanner (System.in);
				grep.limit=Integer.parseInt(nbUtil.nextLine());
				wordLookUp.setLimit(grep.limit);
				wordLookUp.tsvStats(tsvPath, chosenPath, column, queryUtil);
			
			}
			
			if (grep.statsPerAuthorYear!=null&&!grep.statsPerAuthorYear.isEmpty()){
				HashMap<String, int[]>combinedStats=combine.combine(grep.statsPerAuthorYear, valueAsked);
				
				for (Entry<String, int[]>entry:combinedStats.entrySet()){
					if (entry.getKey().contains(preciseQuery)){
						System.out.println("Voici les stats pour "+preciseQuery);
						System.out.println("Nombre total de tokens : "+entry.getValue()[1]);
						System.out.println("Nombre d'occurrences de "+grep.query+" : "+entry.getValue()[0]);
						if (valueAsked==3){
							for (String []doc:grep.statsPerDoc){
								if (doc[3].contains(preciseQuery)){
									System.out.println("\nPour le fichier : "+doc[5]);
									System.out.println("Nombre total de tokens : "+doc[2]);
									System.out.println("Nombre de matchs : "+doc[1]);
									System.out.println("Fréquence Relative : "+doc[0]);
								}
							}
						}
						else if (valueAsked==4){
							for (String []doc:grep.statsPerDoc){
								if (doc[4].contains(preciseQuery)){
									System.out.println("\nPour le fichier : "+doc[5]);
									System.out.println("Nombre total de tokens : "+doc[2]);
									System.out.println("Nombre de matchs : "+doc[1]);
									System.out.println("Fréquence Relative : "+doc[0]);
								}
							}
						}
					}
				}
				
				System.out.println("\nSouhaitez-vous enregistrer votre requête dans un csv ? (o/n)");
				String save= word.next();	
				String nomFichier=grep.query.replaceAll("\\\\", "")+"_"+grep.nameYearTitle+"_"+grep.form;
				nomFichier=nomFichier.replaceAll("\\s", "_");
				String pathToSave=tsvPath.substring(0, tsvPath.lastIndexOf("/")+1);
				if (save.equals("o")&&(column==colAuthor||column==colYear)){
					ExportData.exportToCSV(pathToSave,nomFichier,grep.statsPerAuthorYear);
					System.out.println("Votre requête a été sauvegardée");
				}
				else if (save.equals("o")&&(column==colTitle)){
					ExportData.exportListToCSV(pathToSave,nomFichier,grep.statsPerDoc);
					System.out.println("Votre requête a été sauvegardée");
				}
				else{
					System.out.println("Votre requête n'a pas été enregistrée");
				}
			}
			
		    
		    
			System.out.println("\nVoulez-vous faire une nouvelle requête ? (o/n)");
			doItAgain= word.next();	
		}
		System.out.println("Fin du programme");
	}

	public int rechercheParNomDateTitrePourTSV (String usersChoice){
		int columnForQuery=0;

		if (usersChoice.equals("nom")){
			columnForQuery=colAuthor;
		}
		else if (usersChoice.equals("date")){
			columnForQuery=colYear;
		}
		else if (usersChoice.equals("titre")){
			columnForQuery=colTitle;
		}
		return columnForQuery;
	}

}
