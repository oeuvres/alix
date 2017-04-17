package alix.grep;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map.Entry;

public class ExportData {
	
	public static void exportToCSV(String folderPath, String fileName,
			HashMap <String, String []> statsAuthor,HashMap <String, String []> statsYear)
	
	{	
		try
		{
			Path path = Paths.get(folderPath);
			
			
			File file =new File(folderPath+fileName+".tsv");
			if (!file.getParentFile().isDirectory()){
				Files.createDirectories(path);
			}
			
			FileWriter writer = new FileWriter(file);

			writer.append("Auteur\t");
			writer.append("Total De Tokens\t");
			writer.append("Occurrences du Mot\t");
			writer.append("Fréquence Relative\t");
			writer.append("Date\t");
			writer.append("Titre\t");
			writer.append('\n');
			
			for (Entry<String, String[]> entry:statsAuthor.entrySet()){
				writer.append(entry.getValue()[3]+"\t");
				writer.append(entry.getValue()[2]+"\t");
				writer.append(entry.getValue()[1]+"\t");
				writer.append(entry.getValue()[0]+"\t");
				writer.append(entry.getValue()[4]+"\t");
				writer.append(entry.getValue()[5]+"\t");
				writer.append('\n');
			}
			
			writer.append("\n");
			writer.append("\n");
			writer.append("Date\t");
			writer.append("Total De Tokens\t");
			writer.append("Occurrences du Mot\t");
			writer.append("Fréquence Relative\t");
			writer.append("Auteur\t");
			writer.append("Titre\t");
			writer.append('\n');
			
			for (Entry<String, String[]> entry:statsYear.entrySet()){
				writer.append(entry.getValue()[4]+"\t");
				writer.append(entry.getValue()[2]+"\t");
				writer.append(entry.getValue()[1]+"\t");
				writer.append(entry.getValue()[0]+"\t");
				writer.append(entry.getValue()[3]+"\t");
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
}
