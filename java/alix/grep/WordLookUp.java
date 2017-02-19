package alix.grep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import alix.fr.Lexik;
import alix.fr.Occ;
import alix.fr.OccSlider;
import alix.fr.Tag;
import alix.fr.Tokenizer;
import alix.fr.query.Query;
import alix.util.Term;

/**
 * La classe contient les méthodes qui ramassent les fichiers, appellent le tokenizer 
 * et appellent le classe de compilation des données
 * Les méthodes de la classe ont besoin des demandes de l'utilisateur, 
 * et modifient au fur et à mesure la map des données pour chaque auteur
 * Après utilisation, la map des données statistiques pour chaque auteur est mise à jour
 * @author user
 *
 */

public class WordLookUp {

	String preciseQuery;
	String query;
	int caseSensitivity;
	String nameYearTitle;
	HashMap<String, String[]>statsPerAuthorYear;
	String form;

	List<String[]>statsPerDoc;
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

	public String getQuery() {
		return query ;	
	}

	public void setStatsPerDoc(List<String[]>statsPerDoc){
		this.statsPerDoc=statsPerDoc;
	}

	public List<String[]> getStatsPerDoc(){
		return statsPerDoc;
	}

	public int getCaseSensitivity() {
		return caseSensitivity;
	}

	public void setCaseSensitivity(int query) {
		this.caseSensitivity = query;
	}

	public String getNameYearTitle() {
		return nameYearTitle;
	}

	public void setNameYearTitle(String query) {
		this.nameYearTitle = query;
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
	public HashMap<String, String[]> oneWord(String chosenPath, int chosenColumn,
			List <String []>allRows) throws IOException{
		System.out.println("Quel mot voulez-vous chercher ?");

		Scanner answer=new Scanner(System.in);
		query = answer.nextLine();

		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);
			int countOccurrences=0;
			String fileName=cells[GrepMultiWordExpressions.colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());
			String text = new String(Files.readAllBytes( path ), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(text);
			long occs = 0;
			Occ occ=new Occ();

			while ( toks.token(occ) ) {
				if ( occ.tag().isPun() ) continue;
				Term term=null;
				if (form.contains("l")){
					term=occ.lem();
				}
				else {
					term=occ.graph();
				}
				occs++;

				Pattern p = Pattern.compile(query, caseSensitivity);
				Matcher m = p.matcher(term.toString());

				if (m.matches()){
					countOccurrences++;
				}
			}

			CombineStats combine=new CombineStats();
			combine.setStatsPerDoc(getStatsPerDoc());
			combine.setStatsPerAuthorYear(getStatsAuthorYear());
			statsPerAuthorYear=combine.mergeData( cells, 
					chosenColumn, countOccurrences, occs, fileName);
			statsPerDoc=combine.getStatsPerDoc();
		}

		System.out.println("\nQuel(le) "+nameYearTitle+" voulez-vous ?");
		setPreciseQuery(answer.nextLine());
		return statsPerAuthorYear;

	}



	@SuppressWarnings("resource")
	public HashMap<String, String[]> severalWords(String chosenPath, 
			int chosenColumn,List <String []>allRows) throws IOException{
		System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
		Scanner motsUtil=new Scanner (System.in);
		query = motsUtil.nextLine();
		HashMap<String, WordFlag>listToCheck=new HashMap<String, WordFlag>();
		String tabDeMots[]=query.split("\\s");
		for (String mot:tabDeMots){
			listToCheck.put(mot, new WordFlag());
		}

		int window=0;
		if (listToCheck.keySet().size()>1){
			System.out.println("Quelle est l'étendue de votre fenêtre (en nombre de mots) ?");
			Scanner answerWord=new Scanner(System.in);
			window = Integer.valueOf(answerWord.next());
		}


		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);

			int countOccurrences=0;
			String fileName=cells[GrepMultiWordExpressions.colCode]+".xml";
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
				Term term=null;
				if (form.contains("l")){
					term=occ.lem();
				}
				else {
					term=occ.graph();
				}
				WordFlag test = listToCheck.get( term );

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

			CombineStats combine=new CombineStats();
			combine.setStatsPerDoc(getStatsPerDoc());
			combine.setStatsPerAuthorYear(getStatsAuthorYear());
			statsPerAuthorYear=combine.mergeData( cells, 
					chosenColumn, countOccurrences, occs, fileName);
			statsPerDoc=combine.getStatsPerDoc();
		}

		System.out.println("\nQuel(le) "+nameYearTitle+" voulez-vous ?");
		Scanner answerLine=new Scanner(System.in);
		setPreciseQuery(answerLine.nextLine());
		return statsPerAuthorYear;
	}

	private class WordFlag {
		private boolean value = false;
	}


	@SuppressWarnings("resource")
	public HashMap<String, String[]> wordAndTags(String chosenPath, int chosenColumn, List <String []>allRows) 
			throws IOException{
		System.out.println("Quel(s) mot(s) voulez-vous chercher ? (si plusieurs, séparez par un espace)");
		Scanner motsUtil=new Scanner (System.in);
		query = motsUtil.nextLine();

		System.out.println("Calcul des matchs en cours...");

		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			String []cells=allRows.get(counterRows);

			int countOccurrences=0;
			String fileName=cells[GrepMultiWordExpressions.colCode]+".xml";
			StringBuilder pathSB=new StringBuilder();
			pathSB.append(chosenPath);
			pathSB.append(fileName);
			Path path = Paths.get(pathSB.toString());

			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

			Occ queryUtil []=qparse(query);

			List<String> nbFound=grep (text,queryUtil);

			countOccurrences+=nbFound.size();

			CombineStats combine=new CombineStats();
			combine.setStatsPerDoc(getStatsPerDoc());
			combine.setStatsPerAuthorYear(getStatsAuthorYear());
			statsPerAuthorYear=combine.mergeData( cells, 
					chosenColumn, countOccurrences, nbOccs, fileName);
			statsPerDoc=combine.getStatsPerDoc();
		}

		System.out.println("\nQuel(le) "+nameYearTitle+" voulez-vous ?");
		Scanner answerLine=new Scanner(System.in);
		setPreciseQuery(answerLine.nextLine());
		return statsPerAuthorYear;


	}


	public void tsvStats(String pathTSV, String pathCorpus, int col, String queries) throws IOException{
		BufferedReader TSVFile = new BufferedReader(new FileReader(pathTSV));
		List<String>globalResults=new ArrayList<String>();
		LinkedHashMap<String,Integer>orderedGlobalResults=new LinkedHashMap<String,Integer>();
		File directory=new File(pathCorpus);

		File []alltexts=directory.listFiles();

		for (File file:alltexts){
			Query q1 = new Query(queries);
			String xmlTest = new String(Files.readAllBytes(  file.toPath()) , StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(xmlTest);
			Occ occ=new Occ();

			while (toks.token(occ) ) {
				if ( q1.test(occ) ) {
					if (occ.tag().toString().contains("NAME")){
						globalResults.add(q1.found().toString());
					}
					else{
						globalResults.add(q1.found().toString().toLowerCase());
					}
				}
			}
		}
		Set<String> uniqueSet = new HashSet<String>(globalResults);
		for (String temp : uniqueSet) {
			orderedGlobalResults.put(temp, Collections.frequency(globalResults, temp));
		}

		orderedGlobalResults=sortMyMapByValue(orderedGlobalResults);
		String queryForFile=queries.replaceAll("\\W+", "_");
		File fileGlobal =new File("./test/"+queryForFile+"_globalPatterns.tsv");
		FileWriter writerGlobal = new FileWriter(fileGlobal);
		Path path1=Paths.get("./test/");
		if (!fileGlobal.getParentFile().isDirectory()){
			Files.createDirectories(path1);
		}

		writerGlobal.append("Pattern\t");
		writerGlobal.append("Nombre\t");
		writerGlobal.append('\n');
		for (Entry<String,Integer>entry:orderedGlobalResults.entrySet()){
			writerGlobal.append(entry.getKey()+"\t");
			writerGlobal.append(entry.getValue()+"\t");
			writerGlobal.append('\n');
		}
		writerGlobal.flush();
		writerGlobal.close();	

		String dataRow = TSVFile.readLine();
		List <String []>allRows=new ArrayList<String[]>();
		String[] dataArray = null;
		while (dataRow != null){

			dataArray = dataRow.split("\t");
			allRows.add(dataArray);
			dataRow = TSVFile.readLine();

		}
		TSVFile.close();
		
		HashMap<String,LinkedHashMap<String,Integer>>mapAuthor=new HashMap<String,LinkedHashMap<String,Integer>>();
		for (int counterRows=1; counterRows<allRows.size(); counterRows++){
			List<String>indivResults=new ArrayList<String>();
			String []cells=allRows.get(counterRows);
			String fileName=cells[GrepMultiWordExpressions.colCode]+".xml";
			String queryEntry=cells[col];
			Query q1 = new Query(queries);
			String xml = new String(Files.readAllBytes( Paths.get( pathCorpus+fileName ) ), StandardCharsets.UTF_8);
			Tokenizer toks = new Tokenizer(xml);
			Occ occ=new Occ();

			while (toks.token(occ) ) {
				if (q1.test(occ) ) {
					if (occ.tag().toString().contains("NAME")){
						indivResults.add(q1.found().toString());
					}
					else{
						indivResults.add(q1.found().toString().toLowerCase());
					}
				}
			}

			LinkedHashMap<String,Integer>findings=new LinkedHashMap<String,Integer>();
			if (mapAuthor.containsKey(queryEntry)){
				findings=mapAuthor.get(queryEntry);
				for (String key:orderedGlobalResults.keySet()){
					if (!findings.containsKey(key)) {
						findings.put(key, Collections.frequency(indivResults, key));
					}
					else{
						int previous=findings.get(key);
						findings.put(key, Collections.frequency(indivResults, key)+previous);
					}
				}
				
			}
			else{
				for (String key:orderedGlobalResults.keySet()){
					if (!findings.containsKey(key)) {
						findings.put(key, Collections.frequency(indivResults, key));
					}
					else{
						int previous=findings.get(key);
						findings.put(key, Collections.frequency(indivResults, key)+previous);
					}
				}
			}
			mapAuthor.put(queryEntry, findings);
		}
		String nameOrYear="";
		if (col==3){
			nameOrYear="name";
		}
		else{
			nameOrYear="year";
		}
		File fileTSV =new File("./test/"+queryForFile+"_"+nameOrYear+"_indivPatterns.tsv");
		FileWriter writer = new FileWriter(fileTSV);
		if (!fileTSV.getParentFile().isDirectory()){
			Files.createDirectories(path1);
		}

		writer.append("Auteur\t");
		writer.append("Pattern\t");
		writer.append("Nombre\t");
		writer.append('\n');
		for (Entry<String,LinkedHashMap<String,Integer>>entry:mapAuthor.entrySet()){	

			for (Entry<String,Integer>values:entry.getValue().entrySet()){
				String value=values.getKey();		
				Integer nb=values.getValue();
				writer.append(entry.getKey()+"\t");
				writer.append(value+"\t");
				writer.append(nb+"\t");
				writer.append('\n');
			}
		}
		writer.flush();
		writer.close();	
	}

	public static LinkedHashMap<String, Integer>sortMyMapByValue(LinkedHashMap<String, Integer>map){
		LinkedHashMap<String, Integer> sortedMap = 
				map.entrySet().stream().
				sorted(Map.Entry.<String, Integer>comparingByValue().reversed()) 
				.limit(11).
				collect(Collectors.toMap(Entry::getKey, Entry::getValue,
						(e1, e2) -> e1, LinkedHashMap::new));
		return sortedMap;
	}


	/**
	 * 
	 * @param text
	 * @param query
	 */
	public List<String> grep( String text, Occ[] query) 
	{
		// fenêtre de mots pour sortir une concordance
		OccSlider win = new OccSlider( 1, 1 );

		long occs=0;
		List<String>found=new ArrayList<String>();
		Tokenizer toks = new Tokenizer(text);
		int qlength = query.length;
		int qlevel = 0;
		Occ occ;
		Set<String>chainOfOcc=new HashSet();
		// ici la ligne délicate, le tokenize met à jour une occurrence dans fenêtre
		while (toks.token( win.add() ) ) {

			occ = win.get( 0 ); // pointeur sur l’occurrence courante
			if ( occ.tag().isPun() ) continue;
			occs++;
			if ( query[qlevel].fit( occ )) {
				// comment arrives-tu à reconstruire la locution trouvée de 1 à plusieurs mots ? 
				int index=qlevel;
				qlevel++;

				if ( qlevel == qlength ) {
					// juste pour déboguage, la sortie d'une concordance peut avoir trois sortie différentes
					//  — console
					//  — fichier
					//  — web
					// pourrait être fixé par le constructuer de l’objet
					found.add(occ.prev().orth().toString()+ " "+ occ.orth().toString());
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
		// loop on a test folder for all files
		String dir = "test/";
		String[] queries = { "littérature ADJ" };
		// test if query is correctly parsed
		// for (Occ occ: query) System.out.println( occ );
		WordLookUp thing = new WordLookUp();
		for (final File src : new File( dir ).listFiles()) {
			if ( src.isDirectory() ) continue;
			if ( src.getName().startsWith( "." )) continue;
			if ( src.getName().startsWith( "_" )) continue;
			String xml = new String(Files.readAllBytes( Paths.get( src.toString() ) ), StandardCharsets.UTF_8);
			System.out.println( src );
			for ( String q: queries) {
				//        System.out.println( "  —— "+q );
				List <String>myList=thing.grep( xml, qparse(q) );
				System.out.println(myList.size());
			}
		}
	}
}
