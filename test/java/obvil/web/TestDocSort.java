package obvil.web;

import java.util.List;

import org.apache.lucene.search.SortField;

import alix.lucene.Alix;

public class TestDocSort
{
  static public void main(String[] args) 
  {
    SortField sf2 = new SortField(Alix.ID, SortField.Type.STRING);
    System.out.println(sf2);
  }
}
