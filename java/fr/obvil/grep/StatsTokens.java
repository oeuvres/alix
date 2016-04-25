/**
 *
 */
package fr.obvil.grep;

/**
 *
 *
 */
public class StatsTokens {
	private String query;
	private int nbTokens;
	private int nbSearchedEntry;
	private String docName;
	private String authorsName;
	private String year;
	private String title;
	/**
	 * @return the query
	 */
	public String getQuery() {
		return query;
	}
	/**
	 * @param query the query to set
	 */
	public void setQuery(String query) {
		this.query = query;
	}
	/**
	 * @return the totalTokens
	 */
	public int getTotal() {
		return nbTokens;
	}
	/**
	 * @param totalTokens the nbOfTokens to set
	 */
	public void setTotal(int totalTokens) {
		this.nbTokens = totalTokens;
	}
	/**
	 * @return the nb of searched token
	 */
	public int getNbEntry() {
		return nbSearchedEntry;
	}
	/**
	 * @param stats the nb of searched token to set
	 */
	public void setStats(int stats) {
		this.nbSearchedEntry = stats;
	}
	/**
	 * @return the doc name
	 */
	public String getDocName() {
		return docName;
	}
	/**
	 * @param docName the docName to set
	 */
	public void setDocName(String docName) {
		this.docName = docName;
	}
	/**
	 * @return the name of each author
	 */
	public String getAuthorsName() {
		return authorsName;
	}
	/**
	 * @param authorsName the name to set
	 */
	public void setAuthorsName(String authorsName) {
		this.authorsName = authorsName;
	}
	/**
	 * @return the year of publication
	 */
	public String getYear() {
		return year;
	}
	/**
	 * @param year the year to set
	 */
	public void setYear(String year) {
		this.year = year;
	}
	/**
	 * @return the title
	 */
	public String getTitle() {
		return title;
	}
	/**
	 * @param title the title to set
	 */
	public void setTitle(String title) {
		this.title = title;
	}
}