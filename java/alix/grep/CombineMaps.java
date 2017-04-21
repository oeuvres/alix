package alix.grep;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;


/**
 * Classe de méthodes qui combinent les nouvelles données des auteurs/dates/titres aux données déjà existantes
 * Les méthodes de la classe ont besoin de la map de stats auteur/date déjà existante et de la liste des stats par titre
 * Après utilisation, la map des données statistiques pour chaque auteur est mise à jour, sous forme de map combinée
 * @author user
 *
 */

public class CombineMaps {
	
	public static final int colCode=2;
	public static final int colAuthor=3;
	public static final int colYear=4;
	static final int colTitle=5;
	
	HashMap<String,String[]>statsPerTitle;
	HashMap<String, String[]>statsPerAuthor;
	HashMap<String, String[]>statsPerYear;
	public void setStatsPerTitle(HashMap<String,String[]>statsPerDoc){
		this.statsPerTitle=statsPerDoc;
	}
	
	public HashMap<String,String[]> getStatsPerDoc(){
		return statsPerTitle;
	}
	
	public HashMap<String, String[]> getStatsAuthor() {
		return statsPerAuthor;
	}

	public void setStatsPerAuthor(HashMap<String, String[]> stats) {
		this.statsPerAuthor = stats;
	}
	
	public HashMap<String, String[]> getStatsYear() {
		return statsPerYear;
	}

	public void setStatsPerYear(HashMap<String, String[]> stats) {
		this.statsPerYear = stats;
	}
	

	public HashMap<String, int[]> combine(HashMap<String, String[]> statsPerAuthorOrYear,
			int valueAsked){
		HashMap<String, int[]>combinedStats=new HashMap<String, int[]>();
		for (Entry<String, String[]>entry:statsPerAuthorOrYear.entrySet()){
			String keyStr=entry.getValue()[valueAsked];
			if (combinedStats.isEmpty()==false&&combinedStats.containsKey(keyStr)){
				int[]stats=new int[2];
				int previousTotalInt=Integer.parseInt(entry.getValue()[1]);
				int previousNbMatches=Integer.parseInt(entry.getValue()[2]);
				stats[0]=previousTotalInt+combinedStats.get(keyStr)[0];
				stats[1]=previousNbMatches+combinedStats.get(keyStr)[1];
				combinedStats.put(entry.getValue()[valueAsked], stats);
			}
			else{
				int[]stats=new int[2];
				stats[0]=Integer.parseInt(entry.getValue()[1]);
				stats[1]=Integer.parseInt(entry.getValue()[2]);
				combinedStats.put(keyStr, stats);
			}
		}
		
		return combinedStats;
		
	}
	
	public void mergeData(String cells[], int countOccurrences, long occs, String fileName){
		String statsPourListeDocs []=new String[7];
		String [] tmp;
		
//		List<String[]>statsPerDoc=grep.getStatsPerDoc();		
			
			if (statsPerAuthor.containsKey(cells[UserInterface.colAuthor])){	
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthor.get(cells[UserInterface.colAuthor])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthor.get(cells[UserInterface.colAuthor])[2])+ occs );
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthor.get(cells[UserInterface.colAuthor])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthor.get(cells[UserInterface.colAuthor])[2])+ occs )); //Relative Frequency
				tmp[3]=cells[UserInterface.colAuthor]; //Authors name
				tmp[4]=statsPerAuthor.get(cells[UserInterface.colAuthor])[4]+" // "+cells[UserInterface.colYear];
				tmp[5]=statsPerAuthor.get(cells[UserInterface.colAuthor])[5]+" // "+cells[UserInterface.colTitle]; // Title
				tmp[6]=fileName;
			}
			else{
				tmp=new String[7];
				tmp[1]=String.valueOf(countOccurrences);
				tmp[2]=String.valueOf( occs );
				tmp[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
				tmp[3]=cells[UserInterface.colAuthor]; //Authors name
				tmp[4]=cells[UserInterface.colYear]; // Year
				tmp[5]=cells[UserInterface.colTitle]; // Title
				tmp[6]=fileName;

			}
			statsPourListeDocs[1]=String.valueOf(countOccurrences);
			statsPourListeDocs[2]=String.valueOf( occs );
			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
			statsPourListeDocs[3]=cells[UserInterface.colAuthor]; //Authors name
			statsPourListeDocs[4]=cells[UserInterface.colYear]; // Year
			statsPourListeDocs[5]=cells[UserInterface.colTitle]; // Title
			statsPourListeDocs[6]=fileName;
			statsPerTitle.put(statsPourListeDocs[5],statsPourListeDocs);
			statsPerAuthor.put(cells[colAuthor], tmp);

			if (statsPerYear.containsKey(cells[UserInterface.colYear])){
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerYear.get(cells[UserInterface.colYear])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerYear.get(cells[UserInterface.colYear])[2])+ occs );
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerYear.get(cells[UserInterface.colYear])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerYear.get(cells[UserInterface.colYear])[2]) + occs )); //Relative Frequency
				tmp[3]=statsPerYear.get(cells[UserInterface.colYear])[3]+" // "+cells[UserInterface.colAuthor]; //Authors name
				tmp[4]=cells[UserInterface.colYear]; // Year
				tmp[5]=statsPerYear.get(cells[UserInterface.colYear])[5]+" // "+cells[UserInterface.colTitle]; // Title
				tmp[6]=fileName;
			}
			else{
				tmp=new String[7];
				tmp[1]=String.valueOf(countOccurrences);
				tmp[2]=String.valueOf( occs );
				tmp[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
				tmp[3]=cells[UserInterface.colAuthor]; //Authors name
				tmp[4]=cells[UserInterface.colYear]; // Year
				tmp[5]=cells[UserInterface.colTitle]; // Title
				tmp[6]=fileName;

			}		
			statsPourListeDocs[1]=String.valueOf(countOccurrences);
			statsPourListeDocs[2]=String.valueOf( occs );
			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
			statsPourListeDocs[3]=cells[UserInterface.colAuthor]; //Authors name
			statsPourListeDocs[4]=cells[UserInterface.colYear]; // Year
			statsPourListeDocs[5]=cells[UserInterface.colTitle]; // Title
			statsPourListeDocs[6]=fileName;
			statsPerTitle.put(statsPourListeDocs[5],statsPourListeDocs);
			statsPerYear.put(cells[UserInterface.colYear],tmp);
			

//			statsPourListeDocs[1]=String.valueOf(countOccurrences);
//			statsPourListeDocs[2]=String.valueOf( occs );
//			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
//			statsPourListeDocs[3]=cells[UserInterface.colAuthor]; //Authors name
//			statsPourListeDocs[4]=cells[UserInterface.colYear]; // Year
//			statsPourListeDocs[5]=cells[UserInterface.colTitle]; // Title
//			statsPourListeDocs[6]=fileName;
//			statsPerDoc.add(statsPourListeDocs);
		}			
	}
	
	

