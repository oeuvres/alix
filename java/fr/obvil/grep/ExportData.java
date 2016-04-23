package fr.obvil.grep;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map.Entry;

public class ExportData {
	public static void exportToCSV(String folderPath, String fileName,HashMap <String, String []> stats)
	{
	
		try
		{
			FileWriter writer = new FileWriter(folderPath+fileName+".csv");

			writer.append("Auteur\t");
			writer.append("Total De Tokens\t");
			writer.append("Occurrences du Mot\t");
			writer.append("Date\t");
			writer.append('\n');
			
			for (Entry<String, String[]> entry:stats.entrySet()){
				writer.append(entry.getValue()[2]+"\t");
				writer.append(entry.getValue()[0]+"\t");
				writer.append(entry.getValue()[1]+"\t");
				writer.append(entry.getValue()[3]+"\t");
				writer.append('\n');
			}
			writer.flush();
			writer.close();
		}
		catch(IOException e)
		{
			e.printStackTrace();
		} 
	}
}
