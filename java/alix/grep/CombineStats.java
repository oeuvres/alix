package alix.grep;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

public class CombineStats {
	
	
	
	

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
			GrepMultiWordExpressions grep,
			HashMap <String, String[]>statsPerAuthorOrYear,
			String cells[], int column, int countOccurrences, long occs, String fileName){
		String statsPourListeDocs []=new String[7];
		String [] tmp;
		List<String[]>statsPerDoc=grep.getStatsPerDoc();
		switch (column){		
		case GrepMultiWordExpressions.colAuthor:			
			if (statsPerAuthorOrYear.containsKey(cells[column])){	
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+ occs );
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+ occs )); //Relative Frequency
				tmp[3]=cells[column]; //Authors name
				tmp[4]=statsPerAuthorOrYear.get(cells[column])[4]+" // "+cells[GrepMultiWordExpressions.colYear]; // Year
				tmp[5]=statsPerAuthorOrYear.get(cells[column])[5]+" // "+cells[GrepMultiWordExpressions.colTitle]; // Title
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
			statsPerAuthorOrYear.put(cells[column], tmp);
			break;

		case GrepMultiWordExpressions.colYear:
			if (statsPerAuthorOrYear.containsKey(cells[column])){
				tmp=new String[7];
				tmp[1]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences);
				tmp[2]=String.valueOf(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2])+ occs );
				tmp[0]=String.valueOf(((Double.parseDouble(statsPerAuthorOrYear.get(cells[column])[1])+countOccurrences)*1000000)/(Integer.parseInt(statsPerAuthorOrYear.get(cells[column])[2]) + occs )); //Relative Frequency
				tmp[3]=statsPerAuthorOrYear.get(cells[column])[3]+" // "+cells[GrepMultiWordExpressions.colAuthor]; //Authors name
				tmp[4]=cells[GrepMultiWordExpressions.colYear]; // Year
				tmp[5]=statsPerAuthorOrYear.get(cells[column])[5]+" // "+cells[GrepMultiWordExpressions.colTitle]; // Title
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

			statsPerAuthorOrYear.put(cells[GrepMultiWordExpressions.colYear],tmp);
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
		grep.setStatsPerDoc(statsPerDoc);
		return statsPerAuthorOrYear;		
	}
	
	
}
