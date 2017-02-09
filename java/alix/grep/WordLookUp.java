package alix.grep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import alix.fr.Occ;
import alix.fr.Tag;
import alix.fr.Tokenizer;

public class WordLookUp {

	static final int colCode=GrepMultiWordExpressions.colCode;
	static final int colAuthor=GrepMultiWordExpressions.colAuthor;
	static final int colYear=GrepMultiWordExpressions.colYear;
	static final int colTitle=GrepMultiWordExpressions.colTitle;
	String preciseQuery;

	public String getPreciseQuery() {
		return preciseQuery;
	}

	public void setPreciseQuery(String query) {
		this.preciseQuery = query;
	}



	public void oneWord(Scanner answer, String query,String chosenPath, 
			int chosenColumn, 
			List <String []>allRows,HashMap <String,String[]>statsPerAuthorOrYear,
			GrepMultiWordExpressions grep, CombineStats combine) throws IOException{
		System.out.println("Quel mot voulez-vous chercher ?");

		String usersWord = answer.nextLine();

		if (usersWord!=null)grep.setWordRequest(usersWord);

		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);
			int countOccurrences=0;
			System.out.println(cells[colCode]);
			String fileName=cells[colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());
			String text = new String(Files.readAllBytes( path ), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(text);
			long occs = 0;
			Occ occ=new Occ();

			while ( toks.token(occ ) ) {
				if ( occ.tag().isPun() ) continue;
				occs++;
				Pattern p = Pattern.compile(grep.getWordRequest(), grep.getCaseSensitivity());
				Matcher m = p.matcher(occ.orth());
				if (m.find()){
					countOccurrences++;
				}
			}
			statsPerAuthorOrYear=combine.mergeData(grep,statsPerAuthorOrYear, cells, chosenColumn, countOccurrences, occs, fileName);
		}

		System.out.println("\nQuel(le) "+grep.getNameOrYearOrTitleString()+" voulez-vous ?");
		setPreciseQuery(answer.nextLine());
		grep.setWordRequest(usersWord);
	}

	@SuppressWarnings("resource")
	public void severalWords(Scanner answerWord, String query,String chosenPath, 
			int chosenColumn, 
			List <String []>allRows,HashMap <String,String[]>statsPerAuthorOrYear,
			GrepMultiWordExpressions grep,CombineStats combine) throws IOException{
		System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
		Scanner motsUtil=new Scanner (System.in);
		String ligneDeMots = motsUtil.nextLine();
		HashMap<String, WordFlag>listToCheck=new HashMap<String, WordFlag>();
		String tabDeMots[]=ligneDeMots.split("\\s");
		for (String mot:tabDeMots){
			listToCheck.put(mot, new WordFlag());
		}

		int window=0;
		if (listToCheck.keySet().size()>1){
			System.out.println("Quelle est l'étendue de votre fenêtre (en nombre de mots) ?");
			window = Integer.valueOf(answerWord.next());
		}


		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);

			int countOccurrences=0;
			System.out.println(cells[colCode]);
			String fileName=cells[colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(text);
			Occ occ=new Occ();
			long occs = 0;

			int innerWin=-1;
			while (toks.token(occ) ) {
				if ( occ.tag().isPun() ) continue;
				occs++;

				WordFlag test = listToCheck.get( occ.orth() );

				if ( test != null ) {
					test.value = true;
					if (innerWin<0) innerWin = 0;
				}

				if (innerWin==window) {
					int nbTrue=0;
					for (Entry <String, WordFlag>entry:listToCheck.entrySet()){
						if (entry.getValue().value == true){
							nbTrue++;
							entry.getValue().value = false;
						}
					}
					if (nbTrue==listToCheck.keySet().size()){
						countOccurrences++;
						innerWin=-1;
					}
				}

				if (innerWin>-1){
					innerWin++;
				}
			}


			statsPerAuthorOrYear=combine.mergeData(grep,statsPerAuthorOrYear, cells, chosenColumn, countOccurrences, occs, fileName);
		}

		System.out.println("\nQuel(le) "+grep.getNameOrYearOrTitleString()+" voulez-vous ?");
		Scanner answerLine=new Scanner(System.in);
		setPreciseQuery(answerLine.nextLine());
		grep.setWordRequest(ligneDeMots);
	}

	private class WordFlag {
		private boolean value = false;
	}


	public void wordAndTags(Scanner answerWord, String query,String chosenPath, 
			int chosenColumn, 
			List <String []>allRows,HashMap <String,String[]>statsPerAuthorOrYear,
			GrepMultiWordExpressions grep,CombineStats combine) throws IOException{
		System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
		Scanner motsUtil=new Scanner (System.in);
		String ligneDeMots = motsUtil.nextLine();

		String[] tabMotsUtil=ligneDeMots.split("\\s");

		HashMap <Occ,Boolean>mapOcc=new HashMap();
		int window=tabMotsUtil.length;

		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);

			int countOccurrences=0;
			System.out.println(cells[colCode]);
			String fileName=cells[colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());

			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

			Tokenizer toks = new Tokenizer(text);
			Occ occ=new Occ();
			long occs = 0;
			int inner=-1;

			while (toks.token(occ) ) {

				if ( occ.tag().isPun() ) continue;
				occs++;
				
				for (String test:tabMotsUtil){
					
					if (occ.tag().toString().contains(test)){				
						mapOcc.put(occ, true);
						if (inner<0) inner = 0;
					}
					else if (occ.orth().toString().contains(test)){
						mapOcc.put(occ, true);
						if (inner<0) inner = 0;
					}
				}
				
				if (inner==window){
					int nbTrue=0;
					for (Entry <Occ, Boolean>entry:mapOcc.entrySet()){			
						if (entry.getValue() == true){
							nbTrue++;
							entry.setValue(false);
						}
					}
					if (nbTrue==window){
						countOccurrences++;
						inner=-1;
					}
				}
				if (inner>-1){
					inner++;
				}

			}

			statsPerAuthorOrYear=combine.mergeData(grep,statsPerAuthorOrYear, cells, chosenColumn, countOccurrences, occs, fileName);
		}

		System.out.println("\nQuel(le) "+grep.getNameOrYearOrTitleString()+" voulez-vous ?");
		Scanner answerLine=new Scanner(System.in);
		setPreciseQuery(answerLine.nextLine());
		grep.setWordRequest(ligneDeMots);


	}

}
