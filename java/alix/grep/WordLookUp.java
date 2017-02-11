package alix.grep;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import alix.fr.Lexik;
import alix.fr.Occ;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.frwork.Presse;

public class WordLookUp {

	static final int colCode=GrepMultiWordExpressions.colCode;
	static final int colAuthor=GrepMultiWordExpressions.colAuthor;
	static final int colYear=GrepMultiWordExpressions.colYear;
	static final int colTitle=GrepMultiWordExpressions.colTitle;
	String preciseQuery;
	long nbOccs;

	public String getPreciseQuery() {
		return preciseQuery;
	}

	public void setPreciseQuery(String query) {
		this.preciseQuery = query;
	}

	public void setNbOcc(long occs) {
		this.nbOccs = occs;
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
			//			System.out.println(cells[colCode]);
			String fileName=cells[colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());
			String text = new String(Files.readAllBytes( path ), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(text);
			long occs = 0;
			Occ occ=new Occ();
			String request=grep.getWordRequest();
			if (cells[colCode].contains("0_1821_voltaire")){
				System.out.println(text);
			}

			while ( toks.token(occ) ) {
				if ( occ.tag().isPun() ) continue;
				occs++;

				Pattern p = Pattern.compile(grep.getWordRequest(), grep.getCaseSensitivity());
				Matcher m = p.matcher(occ.orth().toString());

				if (m.find()){
					countOccurrences++;
				}
			}
			if (cells[colCode].contains("0_1821_voltaire")){
				System.out.println(countOccurrences);
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

			Occ queryUtil []=qparse(ligneDeMots);

			List<String> nbFound=grep (text,queryUtil);

			countOccurrences+=nbFound.size();

			statsPerAuthorOrYear=combine.mergeData(grep,statsPerAuthorOrYear, cells, chosenColumn, countOccurrences, nbOccs, fileName);
		}

		System.out.println("\nQuel(le) "+grep.getNameOrYearOrTitleString()+" voulez-vous ?");
		Scanner answerLine=new Scanner(System.in);
		setPreciseQuery(answerLine.nextLine());
		grep.setWordRequest(ligneDeMots);


	}
	/**
	 * @param text
	 * @param query
	 */
	public List<String> grep( String text, Occ[] query) 
	{
		long occs=0;
		List<String>found=new ArrayList<String>();
		Tokenizer toks = new Tokenizer(text);
		int qlength = query.length;
		int qlevel = 0;
		Occ occ = new Occ();
		while (toks.token(occ) ) {
			if ( occ.tag().isPun() ) continue;
			occs++;
			if ( query[qlevel].fit( occ )) {
				found.add(occ.orth().toString());

				qlevel++;
				if ( qlevel == qlength ) {
					qlevel = 0;
				}
			}
			else qlevel = 0;
		}
		setNbOcc(occs);
		return found;
	}
	/**
	 * Query parser
	 */
	static public Occ[] qparse (String q) {
		String[] parts = q.split( "\\s+" );
		Occ[] query = new Occ[parts.length];
		String s;
		String lem;
		int tag;
		for ( int i =0; i < parts.length; i++ ) {
			s = parts[i];
			// un mot entre guillemets, une forme orthographique
			if ( s.charAt( 0 ) == '"') {
				// une occurrence avec juste un orth
				query[i] = new Occ( null, s.substring( 1, s.length()-2 ), null, null );
				continue;
			}
			// un Tag connu ?
			if ( (tag = Tag.code( s )) != Tag.UNKNOWN ) {
				query[i] = new Occ( null, null, tag, null );
				continue;
			}
			// un lemme connu ?
			if ( s.equals( Lexik.lem( s ) )) {
				query[i] = new Occ( null, null, null, s );
				continue;
			}
			// cas par défaut, une forme graphique
			query[i] = new Occ( null, s, null, null );
		}
		return query;
	}

	/**
	 * Test the Class
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String args[]) throws IOException 
	{
		String text = " Je vous trouve très beau. — Mais je ne suis pas beau ! "
				+ " — au moins vous n’êtes pas idiot ";
		String q = "être ADV ADJ";
		Occ[] query = qparse(q);
		// for (Occ occ: query) System.out.println( occ );
		//		grep( text, query);
	}


}
