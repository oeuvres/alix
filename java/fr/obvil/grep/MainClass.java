package fr.obvil.grep;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import site.oeuvres.fr.Tokenizer;

import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public class MainClass {

	public static final String DEFAULT_PATH="/home/bilbo/Téléchargements/critique2000-gh-pages/tei";
	public static final String OBVIL_PATH="http://obvil-dev.paris-sorbonne.fr/corpus/critique";
	public static final String GITHUB_PATH="http://obvil.github.io/critique2000/tei";
	public static String WORD="littérature";

	public static void main(String[] args) throws MalformedURLException, SAXException, IOException, ParserConfigurationException {
		@SuppressWarnings("resource")
		Scanner usersPath=new Scanner(System.in);
		TsvParserSettings settings = new TsvParserSettings();
		TsvParser parser = new TsvParser(settings);
		List<String[]> allRows = parser.parseAll(new File ("./Source/critique2000.tsv"));
		HashSet <StatsTokens>statsPerDoc=new HashSet<StatsTokens>();
		int columnForQuery=0;

		String doItAgain="";
		String chosenPath="";
		
		System.out.println("Définissez le chemin vers vos fichiers à analyser (exemple : /home/bilbo/Téléchargements/critique2000-gh-pages/tei)");
		chosenPath=usersPath.nextLine();
		if(chosenPath.equals(null))chosenPath=DEFAULT_PATH;
		
		while (!doItAgain.equals("non")){

			@SuppressWarnings("resource")
			Scanner usersChoice = new Scanner(System.in); 
			System.out.println("Quel mot voulez-vous chercher ?");
			String usersWord = usersChoice.next();

			if (usersWord!=null){
				WORD=usersWord;
			}

			System.out.println("Recherche par nom ou par date (réponses : nom/date) :");

			String nameOrYear = usersChoice.next();

			while(!nameOrYear.equals("nom")&&!nameOrYear.equals("date")){
				System.out.println("**********");
				System.out.println("Veuillez rentrer le mot nom ou le mot date");
				System.out.println("**********");
				System.out.println("Recherche par nom ou par date (réponses : nom/date) :");
				nameOrYear = usersChoice.next();
			}
			if (nameOrYear.equals("nom")){
				columnForQuery=1;
				System.out.println("Vous demandez les statistiques par nom, calcul en cours...");
			}
			else if (nameOrYear.equals("date")){
				columnForQuery=2;
				System.out.println("Vous demandez les statistiques par date, calcul en cours...");
			}

			long time = System.nanoTime();
			for (int counterRows=1; counterRows<allRows.size(); counterRows++){

				String []cells=allRows.get(counterRows);
				int countOccurrences=0;
				String fileName=cells[0].substring(cells[0].lastIndexOf("/"), cells[0].length());
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
				mapOccurrences.setAuthorsName(cells[1]);
				mapOccurrences.setYear(cells[2]);
				statsPerDoc.add(mapOccurrences);
			}

			System.out.println("\nTemps de repérage des matchs : "+(System.nanoTime() - time) / 1000000);

			System.out.println("\nQuel(le) "+nameOrYear+" voulez-vous ?");
			usersChoice=new Scanner(System.in);
			String preciseQuery = usersChoice.nextLine();

			HashMap<String, String[]>combinedStats=new HashMap<String, String[]>();

			for (StatsTokens entry:statsPerDoc){
				if (combinedStats.isEmpty()==false&&combinedStats.containsKey(entry.getQuery())){
					String stats[]=new String[4];
					double totalTmp=entry.getTotal()+Double.parseDouble(combinedStats.get(entry.getQuery())[0]);
					stats[0]=String.valueOf(totalTmp);
					double tokenTmp=entry.getNbEntry()+Double.parseDouble(combinedStats.get(entry.getQuery())[1]);
					stats[0]=String.valueOf(totalTmp);
					stats[1]=String.valueOf(tokenTmp);
					stats[2]=entry.getAuthorsName();
					stats[3]=entry.getYear();
					combinedStats.put(entry.getQuery(), stats);
				}
				else{
					String stats[]=new String[4];
					stats[0]=String.valueOf(entry.getTotal());
					stats[1]=String.valueOf(entry.getNbEntry());
					stats[2]=entry.getAuthorsName();
					stats[3]=entry.getYear();
					combinedStats.put(entry.getQuery(), stats);
				}
			}

			if (combinedStats.containsKey(preciseQuery)){
				System.out.println("Voici les stats pour "+preciseQuery);
				System.out.println("Nombre total de tokens : "+combinedStats.get(preciseQuery)[0]);
				System.out.println("Nombre d'occurrences de "+usersWord+" : "+combinedStats.get(preciseQuery)[1]);
				System.out.println("*******************************************************");
				for (StatsTokens entry:statsPerDoc){
					if (entry.getQuery().contains(preciseQuery)){
						System.out.println("Nombre total de tokens pour le document "+entry.getDocName()+" : "+entry.getTotal());
						System.out.println("Nombre d'occurrences de "+usersWord+" pour le document "+entry.getDocName()+" : "+entry.getNbEntry());
					}	
				}
			}

			else {
				System.err.println("Il n'y a pas d'entrée correspondant à votre demande");
			}
			
			System.out.println("\nSouhaitez-vous enregistrer votre requête dans un csv ? (oui/non)");
			String save= usersChoice.next();	
			if (save.equals("oui")){
				ExportData.exportToCSV("./TargetCSV/",usersWord,combinedStats);
			}
			else{
				System.out.println("Votre Requête n'a pas été enregistrée");
			}

			System.out.println("\nVoulez-vous faire une nouvelle requête ? (oui/non)");
			doItAgain= usersChoice.next();	
		}
		System.out.println("Fin du programme");
	}
}
