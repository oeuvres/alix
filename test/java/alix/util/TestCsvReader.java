package alix.util;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.text.ParseException;

import alix.fr.Tag;

public class TestCsvReader
{
  public static void words() throws IOException
  {
    Reader reader;
    int i;
    long time;

    for (int loop = 0; loop < 10; loop++) {

      i = -1;
      // HashMap<CharsAtt, LexEntry> dic1 = new HashMap<CharsAtt, LexEntry>();
      // nio is not faster
      // Path path = Paths.get(Tag.class.getResource("word.csv").toURI());
      // reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
      reader = new InputStreamReader(Tag.class.getResourceAsStream("word.csv"));
      CsvReader csv = new CsvReader(reader, 6);
      time = System.nanoTime();
      while (csv.readRow()) {
        i++;
        if (i < 10) System.out.println(""+i+csv.row());
        // dic1.put(new CharsAtt(csv.row().get(0)), new LexEntry(csv.row().get(1),
        // csv.row().get(2)));
      }
      System.out.println("csv: " + ((System.nanoTime() - time) / 1000000) + " ms line=" + i);
    }
  }
  public static void main(String[] args) throws IOException, ParseException, URISyntaxException
  {
    words();
  }

}
