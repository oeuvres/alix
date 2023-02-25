<?xml version="1.0" encoding="UTF-8"?>
<!-- To index TEI files in lucene with Alix LGPL http://www.gnu.org/licenses/lgpl.html 
  © 2019 Frederic.Glorieux@fictif.org & Opteos -->
<xsl:transform version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns="http://www.w3.org/1999/xhtml" 
  xmlns:alix="http://alix.casa" 
  xmlns:epub="http://www.idpf.org/2007/ops"
  xmlns:tei="http://www.tei-c.org/ns/1.0"
  
  extension-element-prefixes="saxon"
  xmlns:saxon="http://saxon.sf.net/"
  
  exclude-result-prefixes="tei">
  <xsl:import href="tei_flow_html.xsl"/>
  <xsl:import href="tei_notes_html.xsl"/>
  <xsl:import href="tei_toc_html.xsl"/>
  <!-- keep xml indent or toc will be… compact -->
  <xsl:output indent="yes" encoding="UTF-8" method="xml" omit-xml-declaration="yes"/>
  <!-- chapter split policy -->
  <xsl:key name="split" match="
    tei:*[self::tei:div or self::tei:div1 or self::tei:div2][normalize-space(.) != ''][@type][
    contains(@type, 'article')
    or contains(@type, 'chapter')
    or contains(@subtype, 'split')
    or contains(@type, 'act')
    or contains(@type, 'poem')
    or contains(@type, 'letter')
    ]
    | tei:group/tei:text
    " use="generate-id(.)"/>
  <xsl:variable name="idHigh"
    select="/*/tei:teiHeader/tei:encodingDesc/tei:refsDecl/tei:citeStructure/@use = '@xml:id'"/>
  <!-- Name of file, provided by caller -->
  <xsl:param name="filename"/>
  <!-- Get metas as a global var to insert fields in all chapters -->
  <xsl:variable name="info">
    <alix:field name="title" type="category" value="{normalize-space($doctitle)}"/>
    <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt">
      <xsl:for-each select="tei:author|tei:principal">
        <xsl:variable name="value">
          <xsl:apply-templates select="." mode="key"/>
        </xsl:variable>
        <xsl:if test="position() = 1">
          <alix:field name="author1" type="category" value="{normalize-space($value)}"/>
        </xsl:if>
        <alix:field name="author" type="facet" value="{normalize-space($value)}"/>
      </xsl:for-each>
    </xsl:for-each>
    <xsl:if test="$byline != ''">
      <alix:field name="byline" type="store">
        <xsl:copy-of select="$byline"/>
      </alix:field>
    </xsl:if>
  </xsl:variable>
  <!-- an html bibliographic line -->
  <xsl:variable name="bibl-book">
    <xsl:if test="$byline != ''">
      <span class="byline">
        <xsl:copy-of select="$byline"/>
      </span>
    </xsl:if>
    <xsl:if test="$doctitle != ''">
      <xsl:text> </xsl:text>
      <em class="title">
        <xsl:copy-of select="$doctitle"/>
      </em>
    </xsl:if>
    <xsl:variable name="year" select="substring($docdate, 1, 4)"/>
    <xsl:if test="string(number($year)) != 'NaN'">
      <xsl:text> </xsl:text>
      <span class="year">
        <xsl:text>(</xsl:text>
        <xsl:value-of select="$year"/>
        <xsl:text>)</xsl:text>
      </span>
    </xsl:if>
  </xsl:variable>

  <!-- Racine -->
  <xsl:template match="/*">
    <xsl:choose>
      <!-- let it like that for obvie -->
      <xsl:when test="true()">
        <alix:book xmlns:epub="http://www.idpf.org/2007/ops">
          <xsl:call-template name="alix:root"/>
        </alix:book>
      </xsl:when>
      <!--No chapters ? Not OK in alix -->
      <xsl:when test=".//tei:div[key('split', generate-id())]">
        <alix:book xmlns:epub="http://www.idpf.org/2007/ops">
          <xsl:call-template name="alix:root"/>
        </alix:book>
      </xsl:when>
      <xsl:otherwise>
        <alix:document xmlns:epub="http://www.idpf.org/2007/ops">
          <xsl:call-template name="alix:root">
            <xsl:with-param name="doctype">document</xsl:with-param>
          </xsl:call-template>
        </alix:document>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="alix:root">
    <xsl:param name="doctype"/>
    <xsl:attribute name="xml:id">
      <xsl:choose>
        <xsl:when test="/*/@xml:id and /*/@xml:id != ''">
          <xsl:value-of select="@xml:id"/>
        </xsl:when>
        <xsl:when test="$filename != ''">
          <xsl:value-of select="$filename"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:message terminate="no">NO id for this book, will be hard to retrieve. Set xsl:param $filename on call or in your sourcefile /tei:TEI/@xml:id</xsl:message>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:attribute>
    <xsl:copy-of select="$info"/>
    <!-- Date of global book -->
    <xsl:variable name="year" select="substring($docdate, 1, 4)"/>
    <xsl:if test="string(number($year)) != 'NaN'">
      <alix:field name="year" type="int">
        <xsl:attribute name="value">
          <xsl:value-of select="$year"/>
        </xsl:attribute>
      </alix:field>
    </xsl:if>
    <xsl:if test="@type">
      <alix:field name="type" type="category" value="{@type}"/>
    </xsl:if>
    <alix:field name="bibl" type="meta" xmlns="http://www.w3.org/1999/xhtml">
      <xsl:copy-of select="$bibl-book"/>
    </alix:field>
    <alix:field name="toc" type="store" xmlns="http://www.w3.org/1999/xhtml">
      <xsl:call-template name="toc"/>
    </alix:field>
    <xsl:choose>
      <xsl:when test="$doctype = 'document'">
        <alix:field name="text" type="text">
          <article>
            <xsl:apply-templates>
              <xsl:with-param name="level" select="1"/>
            </xsl:apply-templates>
            <xsl:variable name="notes">
              <xsl:call-template name="footnotes"/>
            </xsl:variable>
            <xsl:if test="$notes != ''">
              <xsl:copy-of select="$notes"/>
            </xsl:if>
          </article>
        </alix:field>
      </xsl:when>
      <xsl:otherwise>
        <!-- process chapters -->
        <xsl:apply-templates mode="alix" select="*"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Default mode alix -->
  <xsl:template match="tei:teiHeader" mode="alix"/>
  <xsl:template match="/tei:TEI/tei:text/tei:front" mode="alix"/>
  <xsl:template match="/tei:TEI/tei:text/tei:back" mode="alix"/>
  <xsl:template match="*" mode="alix">
    <xsl:apply-templates select="*" mode="alix"/>
  </xsl:template>

  <!-- Do not output pages -->
  <xsl:template match="tei:pb">
    <xsl:text>&#10;</xsl:text>
  </xsl:template>
  <!-- no output -->
  <xsl:template match="tei:trailer"/>
  <!-- hide speaker from indexation -->
  <xsl:template match="tei:speaker">
    <xsl:processing-instruction name="index_off"/>
    <xsl:value-of select="$lf"/>
    <p class="speaker">
      <xsl:apply-templates/>
    </p>
    <xsl:value-of select="$lf"/>
    <xsl:processing-instruction name="index_on"/>
  </xsl:template>

  <xsl:template mode="alix" match="
    tei:text | tei:group | tei:body |
    tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7
    ">
    <xsl:choose>
      <!-- declared section -->
      <xsl:when test="
           contains(@type, 'article')
        or contains(@type, 'chapter')
        or contains(@subtype, 'split')
        or contains(@type, 'act')
        or contains(@type, 'poem')
        or contains(@type, 'letter')
        ">
        <xsl:call-template name="chapter"/>
      </xsl:when>
      <!-- section container -->
      <xsl:when test="
      descendant::*[
        contains(@type, 'article')
        or contains(@type, 'chapter')
        or contains(@subtype, 'split')
        or contains(@type, 'act')
        or contains(@type, 'poem')
        or contains(@type, 'letter')
       ]">
        <xsl:apply-templates select="*" mode="alix"/>
      </xsl:when>
      <!-- blocks of text, open a chapter -->
      <xsl:when test="tei:argument | tei:l | tei:list | tei:p | tei:s | tei:table">
        <xsl:call-template name="chapter"/>
      </xsl:when>
      <xsl:when test="self::tei:body">
        <xsl:apply-templates select="*" mode="alix"/>
      </xsl:when>
      <!-- Don’t be intelligent
      <xsl:when test="./*//tei:head[contains(., 'Chapitre') or contains(., 'chapitre')]">
        <xsl:apply-templates select="*" mode="alix"/>
      </xsl:when>
      -->
      <!-- maybe not best grain -->
      <xsl:otherwise>
        <xsl:call-template name="chapter"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="next">
    <xsl:choose>
      <xsl:when test="following-sibling::*[1]">
        <xsl:apply-templates select="following-sibling::*[1]" mode="analytic"/>
      </xsl:when>
      <xsl:when test="following::*[1]">
        <xsl:variable name="next"
          select="following::*[1]/descendant::*[contains(' article chapter act poem letter ', @type) or @subtype = 'split'][1]"/>
        <xsl:choose>
          <xsl:when test="$next">
            <xsl:apply-templates select="$next" mode="analytic"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:apply-templates select="following::*[1]" mode="analytic"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="prev">
    <xsl:choose>
      <xsl:when test="preceding-sibling::*[1]">
        <xsl:apply-templates select="preceding-sibling::*[1]" mode="analytic"/>
      </xsl:when>
      <xsl:when test="preceding::*[1][not(ancestor-or-self::tei:teiHeader)]">
        <xsl:apply-templates select="preceding::*[1]" mode="analytic"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>

  <xsl:template match="*" mode="analytic">
    <xsl:for-each
      select="ancestor-or-self::*[not(self::tei:TEI)][not(self::tei:text)][not(self::tei:body)]">
      <xsl:if test="position() != 1"> — </xsl:if>
      <xsl:apply-templates select="." mode="title"/>
    </xsl:for-each>
  </xsl:template>

  <xsl:template name="chapter">
    <alix:chapter>
      <!-- id is here supposed to be unique ; maybe dangerous… -->
      <xsl:if test="@xml:id and $idHigh">
        <xsl:attribute name="xml:id">
          <xsl:value-of select="@xml:id"/>
        </xsl:attribute>
      </xsl:if>
      <xsl:copy-of select="$info"/>
      <!-- local date or replicate book date ? -->
      <xsl:variable name="chapyear" select="substring(@when, 1, 4)"/>
      <xsl:variable name="bookyear" select="substring($docdate, 1, 4)"/>
      <xsl:choose>
        <xsl:when test="string(number($chapyear)) != 'NaN'">
          <alix:field name="year" type="int" value="{$chapyear}"/>
        </xsl:when>
        <xsl:when test="string(number($bookyear)) != 'NaN'">
          <alix:field name="year" type="int" value="{$bookyear}"/>
        </xsl:when>
      </xsl:choose>
      <alix:field name="type" type="category" value="{@type}"/>
      <alix:field name="bibl" type="meta">
        <xsl:call-template name="bibl"/>
      </alix:field>
      <alix:field name="analytic" type="meta">
        <xsl:call-template name="analytic"/>
      </alix:field>
      <xsl:variable name="pages">
        <xsl:call-template name="pages"/>
      </xsl:variable>
      <xsl:if test="$pages != ''">
        <alix:field name="pages" type="meta">
          <xsl:copy-of select="$pages"/>
        </alix:field>
      </xsl:if>
      <xsl:variable name="prev">
        <xsl:call-template name="prev"/>
      </xsl:variable>
      <xsl:if test="$prev != ''">
        <alix:field name="prev" type="store">
          <xsl:value-of select="$prev"/>
        </alix:field>
      </xsl:if>
      <xsl:variable name="next">
        <xsl:call-template name="next"/>
      </xsl:variable>
      <xsl:if test="$next != ''">
        <alix:field name="next" type="store">
          <xsl:value-of select="$next"/>
        </alix:field>
      </xsl:if>
      <alix:field name="text" type="text">
        <article>
          <xsl:apply-templates>
            <xsl:with-param name="level" select="1"/>
          </xsl:apply-templates>
          <xsl:variable name="notes">
            <xsl:call-template name="footnotes"/>
          </xsl:variable>
          <xsl:if test="$notes != ''">
            <xsl:copy-of select="$notes"/>
          </xsl:if>
        </article>
      </alix:field>
    </alix:chapter>
  </xsl:template>
  <!-- Terms in subtitle could be boosted ? -->
  <xsl:template name="children">
    <xsl:for-each select=".//tei:div">
      <alix:field name="child" type="text">
        <xsl:apply-templates select="." mode="title"/>
      </alix:field>
    </xsl:for-each>
  </xsl:template>
  <xsl:template name="parents">
    <xsl:for-each select="ancestor-or-self::*">
      <!-- <xsl:sort order="descending" select="position()"/> -->
      <xsl:choose>
        <xsl:when test="self::tei:TEI"/>
        <xsl:when test="self::tei:text"/>
        <xsl:when test="self::tei:body"/>
        <xsl:otherwise>
          <alix:field name="parent" type="text">
            <xsl:apply-templates select="." mode="title"/>
          </alix:field>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:template>
</xsl:transform>
