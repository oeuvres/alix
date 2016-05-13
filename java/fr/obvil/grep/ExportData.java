package fr.obvil.grep;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

public class ExportData {
	public static void exportToCSV(String folderPath, String fileName,HashMap <String, String []> stats)
	{
	
		try
		{
			FileWriter writer = new FileWriter(folderPath+fileName+".tsv");

			writer.append("Auteur\t");
			writer.append("Total De Tokens\t");
			writer.append("Occurrences du Mot\t");
			writer.append("Fréquence Relative\t");
			writer.append("Date\t");
			writer.append("Titre\t");
			writer.append('\n');
			
			for (Entry<String, String[]> entry:stats.entrySet()){
				writer.append(entry.getValue()[3]+"\t");
				writer.append(entry.getValue()[2]+"\t");
				writer.append(entry.getValue()[1]+"\t");
				writer.append(entry.getValue()[0]+"\t");
				writer.append(entry.getValue()[4]+"\t");
				writer.append(entry.getValue()[5]+"\t");
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
	public static void exportListToCSV(String folderPath, String fileName,List <String []> stats)
	{
	
		try
		{
			FileWriter writer = new FileWriter(folderPath+fileName+".tsv");

			writer.append("Auteur\t");
			writer.append("Total De Tokens\t");
			writer.append("Occurrences du Mot\t");
			writer.append("Fréquence Relative\t");
			writer.append("Date\t");
			writer.append("Titre\t");
			writer.append('\n');
			
			for (String entry[]:stats){
				writer.append(entry[3]+"\t");
				writer.append(entry[2]+"\t");
				writer.append(entry[1]+"\t");
				writer.append(entry[0]+"\t");
				writer.append(entry[4]+"\t");
				writer.append(entry[5]+"\t");
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
