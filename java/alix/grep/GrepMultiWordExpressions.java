package alix.grep;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import alix.fr.Tokenizer;

public class GrepMultiWordExpressions {

	public static final String DEFAULT_PATH="C:/Users/Administrateur/Desktop/textes/";
	public static final String DEFAULT_TSV="./Source/critique2000.tsv";
	public String WORD="littérature";
	String nameOrYearOrTitle="";
	int caseSensitivity=0;
	List<String[]>statsPerDoc;

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
		GrepMultiWordExpressions maClasse=new GrepMultiWordExpressions();
		Scanner line=new Scanner(System.in);
		Scanner word=new Scanner(System.in);
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

		String doItAgain="";
		String chosenPath="";

		System.out.println("Définissez le chemin vers vos fichiers à analyser (exemple : /home/bilbo/Téléchargements/critique2000-gh-pages/tei/)");
		chosenPath=line.nextLine();

		if(chosenPath.equals(null)||chosenPath.equals(""))chosenPath=DEFAULT_PATH;

		while (!doItAgain.equals("non")){
			HashMap <String,String[]>statsPerAuthorOrYear=new HashMap<String, String[]>();
			maClasse.setStatsPerDoc(new ArrayList<String[]>());

			System.out.println("Souhaitez-vous un tsv regroupé par par nom, par date ou par titre ? (réponses : nom/date/titre) :");
			maClasse.setNameOrYearOrTitleString(word.next());

			int column=maClasse.rechercheParNomDateTitrePourTSV(maClasse.getNameOrYearOrTitleString());

			String preciseQuery="";

			System.out.println("Quelle type de recherche voulez-vous effectuer ? (rentrer le numéro correspondant et taper \"entrée\")");
			System.out.println("1 : rechercher un seul mot, une expression ou une expression régulière");
			System.out.println("2 : rechercher deux mots dans une fenêtre à définir");
			int chooseTypeRequest = Integer.valueOf(word.next());

			System.out.println("Votre requête doit-elle être sensible à la casse ? (sensible/insensible)");
			String casse=word.next();

			if (casse.equals("sensible")){
				maClasse.setCaseSensitivity(0);
			}
			else{
				maClasse.setCaseSensitivity(Pattern.CASE_INSENSITIVE);
			}


			switch (chooseTypeRequest){
			case 1 :

				System.out.println("Quel mot voulez-vous chercher ?");

				String usersWord = line.nextLine();

				if (usersWord!=null)maClasse.setWordRequest(usersWord);

				System.out.println("Calcul des matchs en cours...");

				for (int counterRows=1; counterRows<allRows.size(); counterRows++){
					String []cells=allRows.get(counterRows);
					int countOccurrences=0;
					String fileName=cells[2]+".xml";
					StringBuilder pathSB=new StringBuilder();
					pathSB.append(chosenPath);
					pathSB.append(fileName);
					Path path = Paths.get(pathSB.toString());
					String text = new String(Files.readAllBytes( path ), StandardCharsets.UTF_8);
					Tokenizer toks = new Tokenizer(text);
					StringBuilder sbToks=new StringBuilder();
					while( toks.read() ) {		
						sbToks.append(toks.getString()+" ");
					}
					Pattern p = Pattern.compile("\\s"+maClasse.getWordRequest()+"\\s", maClasse.getCaseSensitivity());
					Matcher m = p.matcher(sbToks.toString());
					while (m.find()){
						countOccurrences++;
					}

					statsPerAuthorOrYear=maClasse.mergeDatas(statsPerAuthorOrYear, maClasse.getStatsPerDoc(), cells, column, countOccurrences, toks, fileName);
				}

				System.out.println("\nQuel(le) "+maClasse.getNameOrYearOrTitleString()+" voulez-vous ?");
				preciseQuery = line.nextLine();
				maClasse.setWordRequest(usersWord);
				break;

			case 2 :
				System.out.println("Quel premier mot voulez-vous chercher ?");
				String firstUsersWord = word.next();
				System.out.println("Quel second mot voulez-vous chercher ?");
				String secondUsersWord = word.next();
				System.out.println("Quelle est l'étendue de votre fenêtre (en nombre de mots) ?");
				int usersWindow = Integer.valueOf(word.next());

				System.out.println("Calcul des matchs en cours...");

				for (int counterRows=1; counterRows<allRows.size(); counterRows++){
					String []cells=allRows.get(counterRows);

					int countOccurrences=0;
					String fileName=cells[2]+".xml";
					StringBuilder pathSB=new StringBuilder();
					pathSB.append(chosenPath);
					pathSB.append(fileName);
					Path path = Paths.get(pathSB.toString());
					String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
					Tokenizer toks = new Tokenizer(text);
					LinkedList<String>listTokens=new LinkedList<String>();
					StringBuilder sbToks=new StringBuilder();
					while( toks.read() ) {		
						listTokens.add(toks.getString());
						sbToks.append(toks.getString()+" ");
					}

					Pattern p = Pattern.compile(firstUsersWord+"\\s[^\\p{L}]*(\\p{L}+(\\s[^\\w])*\\s){1,"+usersWindow+"}"+secondUsersWord, maClasse.getCaseSensitivity());
					Matcher m = p.matcher(sbToks.toString());

					while(m.find()) {
						countOccurrences++;
					}

					Pattern p2 = Pattern.compile(secondUsersWord+"\\s[^\\p{L}]*(\\p{L}+(\\s[^\\w])*\\s){1,"+usersWindow+"}"+firstUsersWord, maClasse.getCaseSensitivity());
					Matcher m2 = p2.matcher(sbToks.toString());

					while(m2.find()) {
						countOccurrences++;
					}

					statsPerAuthorOrYear=maClasse.mergeDatas(statsPerAuthorOrYear, maClasse.getStatsPerDoc(), cells, column, countOccurrences, toks, fileName);
				}

				System.out.println("\nQuel(le) "+maClasse.getNameOrYearOrTitleString()+" voulez-vous ?");
				preciseQuery = line.nextLine();
				maClasse.setWordRequest(firstUsersWord+" et "+secondUsersWord);
				break;
			}

			HashMap<String, int[]>combinedStats=new HashMap<String, int[]>();

			for (Entry<String, String[]>entry:statsPerAuthorOrYear.entrySet()){
				String keyStr=entry.getValue()[column];
				if (combinedStats.isEmpty()==false&&combinedStats.containsKey(keyStr)){
					int[]stats=new int[2];
					int previousTotalInt=Integer.parseInt(entry.getValue()[1]);
					int previousNbMatches=Integer.parseInt(entry.getValue()[2]);
					stats[0]=previousTotalInt+combinedStats.get(keyStr)[0];
					stats[1]=previousNbMatches+combinedStats.get(keyStr)[1];
					combinedStats.put(entry.getValue()[column], stats);
				}
				else{
					int[]stats=new int[2];
					stats[0]=Integer.parseInt(entry.getValue()[1]);
					stats[1]=Integer.parseInt(entry.getValue()[2]);
					combinedStats.put(keyStr, stats);
				}
			}

			for (Entry<String, int[]>entry:combinedStats.entrySet()){
				if (entry.getKey().contains(preciseQuery)){
					System.out.println("Voici les stats pour "+preciseQuery);
					System.out.println("Nombre total de tokens : "+entry.getValue()[1]);
					System.out.println("Nombre d'occurrences de "+maClasse.getWordRequest()+" : "+entry.getValue()[0]);
					if (column==3){
						for (String []doc:maClasse.getStatsPerDoc()){
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
			System.out.println("\nSouhaitez-vous enregistrer votre requête dans un csv ? (oui/non)");
			String save= word.next();	
			String nomFichier=maClasse.getWordRequest().replaceAll("\\\\", "");
			nomFichier=nomFichier.replaceAll("\\s", "_");
			if (save.equals("oui")&&(column==3||column==4)){
				ExportData.exportToCSV("./TargetCSV/",nomFichier,statsPerAuthorOrYear);
			}
			else if (save.equals("oui")&&(column==5)){
				ExportData.exportListToCSV("./TargetCSV/",nomFichier,maClasse.getStatsPerDoc());
			}
			else{
				System.out.println("Votre requête n'a pas été enregistrée");
			}

			System.out.println("\nVoulez-vous faire une nouvelle requête ? (oui/non)");
			doItAgain= word.next();	
		}
		System.out.println("Fin du programme");
	}

	public int rechercheParNomDateTitrePourTSV (String usersChoice){
		int columnForQuery=0;

		if (usersChoice.equals("nom")){
			columnForQuery=3;
		}
		else if (usersChoice.equals("date")){
			columnForQuery=4;
		}
		else if (usersChoice.equals("titre")){
			columnForQuery=5;
		}
		return columnForQuery;
	}

	public HashMap <String, String[]> mergeDatas(HashMap <String, String[]>statsPerAuthorOrYear, List<String[]>statsPerDoc, String cells[], int column, int countOccurrences, Tokenizer toks, String fileName){
		String statsPourListeDocs []=new String[7];
		String [] tmp;
		switch (column){		
		case 3:			
			if (statsPerAuthorOrYear.containsKey(cells[column])){	
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+toks.size);
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+toks.size)); //Relative Frequency
				tmp[column]=cells[column]; //Authors name
				tmp[4]=statsPerAuthorOrYear.get(cells[column])[4]+" // "+cells[4]; // Year
				tmp[5]=statsPerAuthorOrYear.get(cells[column])[5]+" // "+cells[5]; // Title
				tmp[6]=fileName;

			}
			else{
				tmp=new String[7];
				tmp[1]=String.valueOf(countOccurrences);
				tmp[2]=String.valueOf(toks.size);
				tmp[0]=String.valueOf((countOccurrences*1000000)/toks.size); //Relative Frequency
				tmp[column]=cells[column]; //Authors name
				tmp[4]=cells[4]; // Year
				tmp[5]=cells[5]; // Title
				tmp[6]=fileName;
			}
			statsPerAuthorOrYear.put(cells[column], tmp);
			break;
		case 4:
			if (statsPerAuthorOrYear.containsKey(cells[column])){
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+toks.size);
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+toks.size)); //Relative Frequency
				tmp[3]=statsPerAuthorOrYear.get(cells[column])[3]+" // "+cells[3]; //Authors name
				tmp[4]=cells[column]; // Year
				tmp[5]=statsPerAuthorOrYear.get(cells[column])[5]+" // "+cells[5]; // Title
				tmp[6]=fileName;
			}
			else{
				tmp=new String[7];
				tmp[1]=String.valueOf(countOccurrences);
				tmp[2]=String.valueOf(toks.size);
				tmp[0]=String.valueOf((countOccurrences*1000000)/toks.size); //Relative Frequency
				tmp[3]=cells[3]; //Authors name
				tmp[4]=cells[column]; // Year
				tmp[5]=cells[5]; // Title
				tmp[6]=fileName;
			}		
			statsPerAuthorOrYear.put(cells[column],tmp);
			break;
		case 5:
			statsPourListeDocs[1]=String.valueOf(countOccurrences);
			statsPourListeDocs[2]=String.valueOf(toks.size);
			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/toks.size); //Relative Frequency
			statsPourListeDocs[3]=cells[3]; //Authors name
			statsPourListeDocs[4]=cells[4]; // Year
			statsPourListeDocs[5]=cells[5]; // Title
			statsPourListeDocs[6]=fileName;
			statsPerDoc.add(statsPourListeDocs);
			setStatsPerDoc(statsPerDoc);
			break;
		}	
		return statsPerAuthorOrYear;		
	}
}
