package alix.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import alix.fr.Occ;
import alix.grep.GrepMultiWordExpressions;
import alix.grep.WordLookUp;

/**
 * A 2 dimension table,
 * fast access by String keys for rows and cols,
 * build for efficiency of increments of cells,
 * lots of empty cells.
 * 
 * @author glorieux-f
 *
 */
public class SparseMat
{
	/** The col separator for CSV output */
	final char SEP = '\t';
	/** A null value */
	final int NULL=0;
	/** auto counter for cols  */
	int colno = 10;
	/** Hash for datas, by row */
	private HashMap<String, IntVek> rows=new HashMap<String, IntVek>();
	/** Hash for col names */
	private HashMap<String, Integer> colnames=new HashMap<String, Integer>();
	/** Get Value */
	public boolean contains( String rowname, String colname )
	{
		IntVek row = rows.get( rowname );
		if ( row == null ) return false;
		Integer colno = colnames.get( colname );
		if ( colno == null ) return false;
		return  row.contains( colno );
	}
	public int get( String rowname, String colname )
	{
		IntVek row = rows.get( rowname );
		if ( row == null ) return NULL;
		Integer colno = colnames.get( colname );
		if ( colno == null ) return NULL;
		return row.get( colno );
	}
	/** Put value */
	public SparseMat add( String rowname, String colname, int count)
	{
		IntVek row = rows.get( rowname );
		if ( row == null ) {
			row = new IntVek(10);
			rows.put( rowname, row );
		}
		Integer col = colnames.get( colname );
		if ( col == null ) {
			col = colno++;
			colnames.put( colname, col );
		}
		row.add( col, count );
		return this;
	}
	@Override
	public String toString()
	{
		StringBuffer sb = new StringBuffer();
		// get col index in String order of colnames
		SortedSet<String> keys = new TreeSet<String>( colnames.keySet() );
		int width = keys.size();
		int[] colnos = new int[ width ];
		int i = 0;
		for ( String colname : keys ) {
			colnos[ i ] = colnames.get( colname );
			sb.append( SEP ).append( colname );
			i++;
		}
		sb.append( "\n" );
		keys = new TreeSet<String>( rows.keySet() );
		// loop on lines
		for ( String rowname : keys ) {
			sb.append( rowname );
			IntVek row  = rows.get( rowname );
			for ( i = 0; i < width; i++  ) {
				sb.append( SEP );
				if ( !row.contains( colnos[i] )) continue;
				sb.append( row.get( colnos[i] ) );
			}
			sb.append( "\n" );
		}
		return sb.toString();
	}
	
	public static void main(String[] args) throws IOException
	{
		
	}

	

}
