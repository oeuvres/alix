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

public class CombineStats {
	
	List<String[]>statsPerDoc;
	HashMap<String, String[]>statsPerAuthorYear;
	public void setStatsPerDoc(List<String[]>statsPerDoc){
		this.statsPerDoc=statsPerDoc;
	}
	
	public List<String[]> getStatsPerDoc(){
		return statsPerDoc;
	}
	
	public HashMap<String, String[]> getStatsAuthorYear() {
		return statsPerAuthorYear;
	}

	public void setStatsPerAuthorYear(HashMap<String, String[]> stats) {
		this.statsPerAuthorYear = stats;
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
	
	public HashMap <String, String[]> mergeData(
			String cells[], int column, int countOccurrences, long occs, String fileName){
		String statsPourListeDocs []=new String[7];
		String [] tmp;
		
//		List<String[]>statsPerDoc=grep.getStatsPerDoc();
		switch (column){		
		case GrepMultiWordExpressions.colAuthor:			
			if (statsPerAuthorYear.containsKey(cells[column])){	
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthorYear.get(cells[column])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthorYear.get(cells[column])[2])+ occs );
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthorYear.get(cells[column])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthorYear.get(cells[column])[2])+ occs )); //Relative Frequency
				tmp[3]=cells[column]; //Authors name
				tmp[4]=statsPerAuthorYear.get(cells[column])[4]+" // "+cells[GrepMultiWordExpressions.colYear]; // Year
				tmp[5]=statsPerAuthorYear.get(cells[column])[5]+" // "+cells[GrepMultiWordExpressions.colTitle]; // Title
				tmp[6]=fileName;
			}
			else{
				tmp=new String[7];
				tmp[1]=String.valueOf(countOccurrences);
				tmp[2]=String.valueOf( occs );
				tmp[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
				tmp[3]=cells[column]; //Authors name
				tmp[4]=cells[GrepMultiWordExpressions.colYear]; // Year
				tmp[5]=cells[GrepMultiWordExpressions.colTitle]; // Title
				tmp[6]=fileName;

			}
			statsPourListeDocs[1]=String.valueOf(countOccurrences);
			statsPourListeDocs[2]=String.valueOf( occs );
			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
			statsPourListeDocs[3]=cells[GrepMultiWordExpressions.colAuthor]; //Authors name
			statsPourListeDocs[4]=cells[GrepMultiWordExpressions.colYear]; // Year
			statsPourListeDocs[5]=cells[GrepMultiWordExpressions.colTitle]; // Title
			statsPourListeDocs[6]=fileName;
			statsPerDoc.add(statsPourListeDocs);
			statsPerAuthorYear.put(cells[column], tmp);
			break;

		case GrepMultiWordExpressions.colYear:
			if (statsPerAuthorYear.containsKey(cells[column])){
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthorYear.get(cells[column])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthorYear.get(cells[column])[2])+ occs );
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthorYear.get(cells[column])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthorYear.get(cells[column])[2]) + occs )); //Relative Frequency
				tmp[3]=statsPerAuthorYear.get(cells[column])[3]+" // "+cells[GrepMultiWordExpressions.colAuthor]; //Authors name
				tmp[4]=cells[GrepMultiWordExpressions.colYear]; // Year
				tmp[5]=statsPerAuthorYear.get(cells[column])[5]+" // "+cells[GrepMultiWordExpressions.colTitle]; // Title
				tmp[6]=fileName;
			}
			else{
				tmp=new String[7];
				tmp[1]=String.valueOf(countOccurrences);
				tmp[2]=String.valueOf( occs );
				tmp[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
				tmp[3]=cells[GrepMultiWordExpressions.colAuthor]; //Authors name
				tmp[4]=cells[GrepMultiWordExpressions.colYear]; // Year
				tmp[5]=cells[GrepMultiWordExpressions.colTitle]; // Title
				tmp[6]=fileName;

			}		
			statsPourListeDocs[1]=String.valueOf(countOccurrences);
			statsPourListeDocs[2]=String.valueOf( occs );
			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
			statsPourListeDocs[3]=cells[GrepMultiWordExpressions.colAuthor]; //Authors name
			statsPourListeDocs[4]=cells[GrepMultiWordExpressions.colYear]; // Year
			statsPourListeDocs[5]=cells[GrepMultiWordExpressions.colTitle]; // Title
			statsPourListeDocs[6]=fileName;
			statsPerDoc.add(statsPourListeDocs);

			statsPerAuthorYear.put(cells[GrepMultiWordExpressions.colYear],tmp);
			break;
		case GrepMultiWordExpressions.colTitle:
			statsPourListeDocs[1]=String.valueOf(countOccurrences);
			statsPourListeDocs[2]=String.valueOf( occs );
			statsPourListeDocs[0]=String.valueOf((countOccurrences*1000000)/ occs ); //Relative Frequency
			statsPourListeDocs[3]=cells[GrepMultiWordExpressions.colAuthor]; //Authors name
			statsPourListeDocs[4]=cells[GrepMultiWordExpressions.colYear]; // Year
			statsPourListeDocs[5]=cells[GrepMultiWordExpressions.colTitle]; // Title
			statsPourListeDocs[6]=fileName;
			statsPerDoc.add(statsPourListeDocs);
			break;
		}	
		return statsPerAuthorYear;		
	}
	
	
}
