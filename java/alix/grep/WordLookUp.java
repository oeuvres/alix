package alix.grep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import alix.fr.Occ;
import alix.fr.Tokenizer;

public class WordLookUp {
	
	static final int colCode=1;
	static final int colAuthor=2;
	static final int colYear=3;
	static final int colTitle=4;
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
			String fileName=cells[colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());
			String text = new String(Files.readAllBytes( path ), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(text);
			long occs = 0;
			Occ occ; // pointeur sur l’occurrence courante dans le tokeniseur 
			// Ici je me demande ce que tu veux. Lemme ? forme ? graphie ?
			while ( (occ = toks.word( )) != null ) {
				if ( occ.tag().pun() ) continue;
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
	
	public void twoWords(Scanner answerLine,Scanner answerWord, String query,String chosenPath, 
			int chosenColumn, 
			List <String []>allRows,HashMap <String,String[]>statsPerAuthorOrYear,
			GrepMultiWordExpressions grep,CombineStats combine) throws IOException{
		System.out.println("Quel premier mot voulez-vous chercher ?");
		String firstUsersWord = answerWord.next();
		System.out.println("Quel second mot voulez-vous chercher ?");
		String secondUsersWord = answerWord.next();
		System.out.println("Quelle est l'étendue de votre fenêtre (en nombre de mots) ?");
		int usersWindow = Integer.valueOf(answerWord.next());

		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);

			int countOccurrences=0;
			String fileName=cells[colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(text);
			LinkedList<String>listTokens=new LinkedList<String>();
			StringBuilder sbToks=new StringBuilder();
			Occ occ; // occurrence courante
			long occs = 0; // compteur d’occurrence
			while ( (occ = toks.word( )) != null ) {
				if ( occ.tag().pun() ) continue;
				occs++;
				// pour cet usage peut-être que l’objet TermDic peut t’aider
				listTokens.add( occ.orth().toString() ); // 
				sbToks.append( occ.orth()+" ");
			}

			Pattern p = Pattern.compile(firstUsersWord+"\\s[^\\p{L}]*(\\p{L}+(\\s[^\\w])*\\s){1,"+usersWindow+"}"+secondUsersWord, grep.getCaseSensitivity());
			Matcher m = p.matcher(sbToks.toString());

			while(m.find()) {
				countOccurrences++;
			}

			Pattern p2 = Pattern.compile(secondUsersWord+"\\s[^\\p{L}]*(\\p{L}+(\\s[^\\w])*\\s){1,"+usersWindow+"}"+firstUsersWord, grep.getCaseSensitivity());
			Matcher m2 = p2.matcher(sbToks.toString());

			while(m2.find()) {
				countOccurrences++;
			}
			
			statsPerAuthorOrYear=combine.mergeData(grep,statsPerAuthorOrYear, cells, chosenColumn, countOccurrences, occs, fileName);
		}

		System.out.println("\nQuel(le) "+grep.getNameOrYearOrTitleString()+" voulez-vous ?");
		setPreciseQuery(answerLine.nextLine());
		grep.setWordRequest(firstUsersWord+" et "+secondUsersWord);
	}
	
}
