package fr.obvil.grep;

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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import site.oeuvres.fr.Tokenizer;

public class GrepMultiWordExpressions {

	public static final String DEFAULT_PATH="C:/Users/Administrateur/Desktop/textes/";
	public static final String OBVIL_PATH="http:/obvil-dev.paris-sorbonne.fr/corpus/critique/";
	public static final String GITHUB_PATH="http:/obvil.github.io/critique2000/tei/";
	public static String WORD="littérature";
	String nameOrYearOrTitle="";

	public String getNameOrYear() {
		return nameOrYearOrTitle;
	}

	public void setNameOrYear(String query) {
		this.nameOrYearOrTitle = query;
	}

	public static void main(String[] args) throws MalformedURLException, SAXException, IOException, ParserConfigurationException {
		GrepMultiWordExpressions maClasse=new GrepMultiWordExpressions();
		@SuppressWarnings("resource")
		Scanner line=new Scanner(System.in);
		Scanner word=new Scanner(System.in);
		List <String []>allRows=new ArrayList<String[]>();
		BufferedReader TSVFile = new BufferedReader(new FileReader("./Source/critique2000.tsv"));

		String dataRow = TSVFile.readLine();

		while (dataRow != null){
			String[] dataArray = dataRow.split("\t");
			allRows.add(dataArray);
			dataRow = TSVFile.readLine();
		}

		TSVFile.close();

		int columnForQuery=1;

		String doItAgain="";
		String chosenPath="";

		System.out.println("Définissez le chemin vers vos fichiers à analyser (exemple : /home/bilbo/Téléchargements/critique2000-gh-pages/tei/)");
		chosenPath=line.nextLine();

		if(chosenPath.equals(null))chosenPath=DEFAULT_PATH;

		while (!doItAgain.equals("non")){
			HashSet <StatsTokens>statsPerDoc=new HashSet<StatsTokens>();
			String preciseQuery="";

			System.out.println("Quelle type de recherche voulez-vous effectuer ? (rentrer le numéro correspondant et taper \"entrée\")");
			System.out.println("1 : rechercher un seul mot, une expression ou une expression régulière");
			System.out.println("2 : rechercher deux mots dans une fenêtre à définir");
			int chooseTypeRequest = Integer.valueOf(word.next());

			switch (chooseTypeRequest){
			case 1 :

				System.out.println("Quel mot voulez-vous chercher ?");

				String usersWord = line.nextLine();

				if (usersWord!=null)WORD=usersWord;

				maClasse.rechercheParNomDateTitre(word);
				System.out.println("colonne de la query : "+columnForQuery);
				for (int counterRows=1; counterRows<allRows.size(); counterRows++){
					String []cells=allRows.get(counterRows);
					int countOccurrences=0;
					String fileName=cells[2]+".xml";
					StringBuilder pathSB=new StringBuilder();
					pathSB.append(DEFAULT_PATH);
					pathSB.append(fileName);
					Path path = Paths.get(pathSB.toString());
					String text = new String(Files.readAllBytes( path ), StandardCharsets.UTF_8);
					Tokenizer toks = new Tokenizer(text);
					StringBuilder sbToks=new StringBuilder();
					while( toks.read() ) {		
						sbToks.append(toks.getString()+" ");
					}
					Pattern p = Pattern.compile(" "+WORD+" ");
					Matcher m = p.matcher(sbToks.toString());
					while (m.find()){
						countOccurrences++;
					}
					StatsTokens mapOccurrences= new StatsTokens();
					mapOccurrences.setQuery(cells[columnForQuery]);
					mapOccurrences.setStats(countOccurrences);
					mapOccurrences.setTotal(toks.size);
					mapOccurrences.setDocName(fileName);
					mapOccurrences.setAuthorsName(cells[2]);
					mapOccurrences.setYear(cells[3]);
					mapOccurrences.setTitle(cells[4]);
					statsPerDoc.add(mapOccurrences);
				}
				long time = System.nanoTime();
				System.out.println("\nTemps de repérage des matchs : "+(System.nanoTime() - time) / 1000000);

				System.out.println("\nQuel(le) "+maClasse.getNameOrYear()+" voulez-vous ?");
				line=new Scanner(System.in);
				preciseQuery = line.nextLine();
				WORD=usersWord;
				break;

			case 2 :
				System.out.println("Quel premier mot voulez-vous chercher ?");
				String firstUsersWord = word.next();
				System.out.println("Quel second mot voulez-vous chercher ?");
				String secondUsersWord = word.next();
				System.out.println("Quelle est l'étendue de votre fenêtre (en nombre de mots) ?");
				int usersWindow = Integer.valueOf(word.next());
				columnForQuery=maClasse.rechercheParNomDateTitre(word);

				for (int counterRows=1; counterRows<allRows.size(); counterRows++){
					String []cells=allRows.get(counterRows);
					int countOccurrences=0;
					String fileName=cells[2]+".xml";
					StringBuilder pathSB=new StringBuilder();
					pathSB.append(DEFAULT_PATH);
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

					Pattern p = Pattern.compile(" "+firstUsersWord+"[\\s\\w+\\s]{1,"+usersWindow+"}"+secondUsersWord, Pattern.CASE_INSENSITIVE);
					Matcher m = p.matcher(sbToks.toString());

					while(m.find()) {
						countOccurrences++;
					}

					Pattern p2 = Pattern.compile(" "+secondUsersWord+"[\\s\\w+\\s]{1,"+usersWindow+"}"+firstUsersWord, Pattern.CASE_INSENSITIVE);
					Matcher m2 = p2.matcher(sbToks.toString());

					while(m2.find()) {
						countOccurrences++;
					}

					StatsTokens mapOccurrences= new StatsTokens();
					mapOccurrences.setQuery(cells[columnForQuery]);
					mapOccurrences.setStats(countOccurrences);
					mapOccurrences.setTotal(toks.size);
					mapOccurrences.setDocName(fileName);
					mapOccurrences.setAuthorsName(cells[3]);
					mapOccurrences.setYear(cells[4]);
					mapOccurrences.setTitle(cells[5]);
					statsPerDoc.add(mapOccurrences);
				}	
				System.out.println("\nQuel(le) "+maClasse.getNameOrYear()+" voulez-vous ?");
				line=new Scanner(System.in);
				preciseQuery = line.nextLine();
				WORD=firstUsersWord+" et "+secondUsersWord;
				break;
			}

			HashMap<String, String[]>combinedStats=new HashMap<String, String[]>();

			for (StatsTokens entry:statsPerDoc){
				if (combinedStats.isEmpty()==false&&combinedStats.containsKey(entry.getQuery())){
					String stats[]=new String[5];
					int totalTmp=entry.getTotal()+Integer.parseInt(combinedStats.get(entry.getQuery())[0]);
					int tokenTmp=entry.getNbEntry()+Integer.parseInt(combinedStats.get(entry.getQuery())[1]);
					stats[0]=String.valueOf(totalTmp);
					stats[1]=String.valueOf(tokenTmp);
					stats[2]=entry.getAuthorsName();
					stats[3]=entry.getYear();
					stats[4]=entry.getTitle()+" // "+combinedStats.get(entry.getQuery())[4];
					combinedStats.put(entry.getQuery(), stats);
				}
				else{
					String stats[]=new String[5];
					stats[0]=String.valueOf(entry.getTotal());
					stats[1]=String.valueOf(entry.getNbEntry());
					stats[2]=entry.getAuthorsName();
					stats[3]=entry.getYear();
					stats[4]=entry.getTitle();
					combinedStats.put(entry.getQuery(), stats);
				}
			}

			if (combinedStats.containsKey(preciseQuery)){
				System.out.println("Voici les stats pour "+preciseQuery);
				System.out.println("Nombre total de tokens : "+combinedStats.get(preciseQuery)[0]);
				System.out.println("Nombre d'occurrences de "+WORD+" : "+combinedStats.get(preciseQuery)[1]);
				System.out.println("*******************************************************");
				for (StatsTokens entry:statsPerDoc){
					if (entry.getQuery().contains(preciseQuery)){
						System.out.println("Nombre total de tokens pour le document "+entry.getDocName()+" : "+entry.getTotal());
						System.out.println("Nombre d'occurrences de "+WORD+" pour le document "+entry.getDocName()+" : "+entry.getNbEntry());
					}	
				}
			}

			else {
				System.err.println("Il n'y a pas d'entrée correspondant à votre demande");
			}

			System.out.println("\nSouhaitez-vous enregistrer votre requête dans un csv ? (oui/non)");
			String save= word.next();	
			if (save.equals("oui")){
				ExportData.exportToCSV("./TargetCSV/",WORD.replaceAll("\\s", "_"),combinedStats);
			}
			else{
				System.out.println("Votre requête n'a pas été enregistrée");
			}

			System.out.println("\nVoulez-vous faire une nouvelle requête ? (oui/non)");
			doItAgain= word.next();	
		}
		System.out.println("Fin du programme");
	}

	public int rechercheParNomDateTitre (Scanner usersChoice){
		int columnForQuery=0;
		System.out.println("Recherche par nom, par date ou par titre (réponses : nom/date/titre) :");
		setNameOrYear(usersChoice.next());

		while(!nameOrYearOrTitle.equals("nom")&&!nameOrYearOrTitle.equals("date")&&!nameOrYearOrTitle.equals("titre")){
			System.out.println("**********");
			System.out.println("Veuillez rentrer le mot nom ou le mot date");
			System.out.println("**********");
			System.out.println("Recherche par nom ou par date (réponses : nom/date) :");
			nameOrYearOrTitle = usersChoice.next();
		}
		if (nameOrYearOrTitle.equals("nom")){
			columnForQuery=3;
			System.out.println("Vous demandez les statistiques par nom, calcul en cours...");
		}
		else if (nameOrYearOrTitle.equals("date")){
			columnForQuery=4;
			System.out.println("Vous demandez les statistiques par date, calcul en cours...");
		}
		else if (nameOrYearOrTitle.equals("titre")){
			columnForQuery=5;
			System.out.println("Vous demandez les statistiques par titre, calcul en cours...");
		}
		return columnForQuery;
	}
}
