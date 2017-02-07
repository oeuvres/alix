package alix.grep;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

public class GrepMultiWordExpressions {

	public static final String DEFAULT_PATH="/home/odysseus/Téléchargements/critique2000-gh-pages/tei/";
	public static final String DEFAULT_TSV="/home/odysseus/Téléchargements/critique2000-gh-pages/biblio.tsv";
	public String WORD="littérature";
	String nameOrYearOrTitle="";
	int caseSensitivity=0;
	List<String[]>statsPerDoc;

	static final int colCode=1;
	static final int colAuthor=2;
	static final int colYear=3;
	static final int colTitle=4;

	public String getWordRequest() {
		return WORD;
	}

	public void setWordRequest(String query) {
		this.WORD = query;
	}

	public String getNameOrYearOrTitleString() {
		return nameOrYearOrTitle;
	}

	public void setNameOrYearOrTitleString(String query) {
		this.nameOrYearOrTitle = query;
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

	public static void main(String[] args) throws MalformedURLException, SAXException, IOException, ParserConfigurationException {
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

		System.out.println("Définissez le chemin vers vos fichiers à analyser (exemple : /home/bilbo/Téléchargements/critique2000-gh-pages/tei/)");
		chosenPath=line.nextLine();

		if(chosenPath.equals(null)||chosenPath.equals(""))chosenPath=DEFAULT_PATH;

		while (!doItAgain.equals("n")){
			HashMap <String,String[]>statsPerAuthorOrYear=new HashMap<String, String[]>();
			grep.setStatsPerDoc(new ArrayList<String[]>());

			System.out.println("Souhaitez-vous un tsv regroupé par par nom, par date ou par titre ? (réponses : nom/date/titre) :");
			grep.setNameOrYearOrTitleString(word.next());

			int column=grep.rechercheParNomDateTitrePourTSV(grep.getNameOrYearOrTitleString());		
			int valueAsked=0;
			if (column==colYear)valueAsked=4;
			if (column==colAuthor)valueAsked=3;
			if (column==colTitle)valueAsked=5;

			System.out.println("Quelle type de recherche voulez-vous effectuer ? (rentrer le numéro correspondant et taper \"entrée\")");
			System.out.println("1 : rechercher un seul mot, une expression ou une expression régulière");
			System.out.println("2 : rechercher deux mots dans une fenêtre à définir");
			int chooseTypeRequest = Integer.valueOf(word.next());

			System.out.println("Votre requête doit-elle être sensible à la casse ? (o/n)");
			String casse=word.next();

			if (casse.equals("o")){
				grep.setCaseSensitivity(0);
			}
			else{
				grep.setCaseSensitivity(Pattern.CASE_INSENSITIVE);
			}

			WordLookUp wordLookUp=new WordLookUp();
			CombineStats combine=new CombineStats();
			
			switch (chooseTypeRequest){
			case 1 :
				wordLookUp.oneWord(line, wordLookUp.getPreciseQuery(), chosenPath, 
						column, allRows, statsPerAuthorOrYear, 
						grep, combine);
				preciseQuery=wordLookUp.getPreciseQuery();
				break;

			case 2 :
				wordLookUp.twoWords(line, word, wordLookUp.getPreciseQuery(), chosenPath, 
						column, allRows, statsPerAuthorOrYear, 
						grep, combine);
				break;
			}
	
			HashMap<String, int[]>combinedStats=combine.combine(statsPerAuthorOrYear, valueAsked);
			
			for (Entry<String, int[]>entry:combinedStats.entrySet()){
				if (entry.getKey().contains(preciseQuery)){
					System.out.println("Voici les stats pour "+preciseQuery);
					System.out.println("Nombre total de tokens : "+entry.getValue()[1]);
					System.out.println("Nombre d'occurrences de "+grep.getWordRequest()+" : "+entry.getValue()[0]);
					if (valueAsked==3){
						for (String []doc:grep.getStatsPerDoc()){
							if (doc[3].contains(preciseQuery)){
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
			String nomFichier=grep.getWordRequest().replaceAll("\\\\", "");
			nomFichier=nomFichier.replaceAll("\\s", "_");
			if (save.equals("o")&&(column==colAuthor||column==colYear)){
				ExportData.exportToCSV("./TargetCSV/",nomFichier,statsPerAuthorOrYear);
				System.out.println("Votre requête a été sauvegardée");
			}
			else if (save.equals("o")&&(column==colTitle)){
				ExportData.exportListToCSV("./tsv/",nomFichier,grep.getStatsPerDoc());
				System.out.println("Votre requête a été sauvegardée");
			}
			else{
				System.out.println("Votre requête n'a pas été enregistrée");
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
