<?xml version="1.0" encoding="UTF-8"?>
<!-- To index TEI files in lucene with Alix LGPL http://www.gnu.org/licenses/lgpl.html 
  © 2019 Frederic.Glorieux@fictif.org & Opteos -->
<xsl:transform version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns="http://www.w3.org/1999/xhtml" 
  xmlns:alix="https://oeuvres.github.io/alix" 
  xmlns:epub="http://www.idpf.org/2007/ops"
  xmlns:tei="http://www.tei-c.org/ns/1.0"
  exclude-result-prefixes="tei"
  
  xmlns:exslt="http://exslt.org/common"
  xmlns:saxon="http://saxon.sf.net/"
  extension-element-prefixes="exslt saxon"
  
  >
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
  <!-- For links in TOC -->
  <xsl:variable name="split" select=".//tei:div[key('split', generate-id())]"/>
  <!--  clean url -->
  <xsl:variable name="_ext"/>
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
    <!-- rights -->
    <xsl:choose>
      <xsl:when test="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:availability/*">
        <alix:field name="rights" type="meta">
          <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:availability/*">
            <p>
              <xsl:apply-templates/>
            </p>
          </xsl:for-each>
          <!--
          <xsl:apply-templates select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:availability/"/>
          -->
        </alix:field>
      </xsl:when>
    </xsl:choose>

  </xsl:variable>
  
  <!-- tags to add at text level (not the book cover) for better stats -->
  <xsl:variable name="tags">
    <xsl:for-each select="/*/tei:teiHeader/tei:profileDesc/tei:textClass/tei:keywords//tei:term">
      <xsl:variable name="value">
        <xsl:apply-templates select="." mode="key"/>
      </xsl:variable>
      <xsl:if test="normalize-space($value) != ''">
        <alix:field name="term" type="facet" value="{normalize-space($value)}"/>
        <xsl:if test="@type and @type != 'term'">
          <alix:field name="{@type}" type="facet" value="{normalize-space($value)}"/>
        </xsl:if>
      </xsl:if>
    </xsl:for-each>
  </xsl:variable>
  
  <!-- an html bibliographic line -->
  <xsl:variable name="bibl-book">
    <xsl:choose>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type = 'html:title']">
        <xsl:apply-templates select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type = 'html:title']/node()"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type = 'zotero']">
        <xsl:apply-templates select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type = 'zotero']/node()"/>
      </xsl:when>
      <xsl:otherwise>
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
      </xsl:otherwise>
    </xsl:choose>
  </xsl:variable>

  <!-- Root -->
  <xsl:template match="/*">
    <xsl:choose>
      <xsl:when test="@type='article'">
        <alix:article xmlns:epub="http://www.idpf.org/2007/ops" xmlns="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="alix:root">
            <xsl:with-param name="doctype">article</xsl:with-param>
          </xsl:call-template>
        </alix:article>
      </xsl:when>
      <xsl:when test="@type='book'">
        <alix:book xmlns:epub="http://www.idpf.org/2007/ops" xmlns="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="alix:root">
            <xsl:with-param name="doctype">book</xsl:with-param>
          </xsl:call-template>
        </alix:book>
      </xsl:when>
      <xsl:when test="@type='document'">
        <alix:document xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <xsl:call-template name="alix:root">
            <xsl:with-param name="doctype">document</xsl:with-param>
          </xsl:call-template>
        </alix:document>
      </xsl:when>
      <!-- let it like that for obvie -->
      <xsl:when test="true()">
        <alix:book xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <xsl:call-template name="alix:root">
            <xsl:with-param name="doctype">book</xsl:with-param>
          </xsl:call-template>
        </alix:book>
      </xsl:when>
      <!--There are chapters -->
      <xsl:when test=".//tei:div[key('split', generate-id())]">
        <alix:book xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <xsl:call-template name="alix:root">
            <xsl:with-param name="doctype">book</xsl:with-param>
          </xsl:call-template>
        </alix:book>
      </xsl:when>
      <xsl:otherwise>
        <alix:document xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
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
          <xsl:message terminate="no">NO id for this document, will be hard to retrieve. Set xsl:param $filename on call, or set /tei:TEI/@xml:id in your sourcefile</xsl:message>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:attribute>
    <xsl:if test="/*/@cert">
      <alix:field name="cert" type="category" value="{normalize-space(/*/@cert)}"/>
    </xsl:if>

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
    <alix:field name="bibl" type="meta">
      <xsl:copy-of select="$bibl-book"/>
    </alix:field>
    <xsl:if test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type = 'ref']">
      <alix:field name="ref" type="meta">
        <xsl:apply-templates select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type = 'ref']"/>
      </alix:field>
    </xsl:if>
    <alix:field name="toc" type="store">
      <xsl:call-template name="toc">
        <xsl:with-param name="class"/>
      </xsl:call-template>
    </alix:field>
    <xsl:call-template name="links"/>
    <xsl:choose>
      <xsl:when test="$doctype = 'book'">
        <!-- No tags for a book -->
        <!-- process chapters -->
        <xsl:apply-templates mode="alix" select="*"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:copy-of select="$tags"/>
        <alix:field name="text" type="text">
          <article>
            <xsl:choose>
              <xsl:when test="/*/tei:text/tei:front | /*/tei:text/tei:back">
                <xsl:apply-templates select="/*/tei:text/tei:front | /*/tei:text/tei:body | /*/tei:text/tei:back"/>
              </xsl:when>
              <xsl:otherwise>
                <xsl:apply-templates select="/*/tei:text/tei:body/node()"/>
              </xsl:otherwise>
            </xsl:choose>
            <xsl:variable name="notes">
              <xsl:for-each select="/*/tei:text">
                <xsl:call-template name="footnotes"/>
              </xsl:for-each>
            </xsl:variable>
            <xsl:if test="$notes != ''">
              <xsl:copy-of select="$notes"/>
            </xsl:if>
          </article>
        </alix:field>
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
  <xsl:template match="tei:pb" priority="2">
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
      <xsl:if test="@xml:id">
        <xsl:attribute name="xml:id">
          <xsl:value-of select="@xml:id"/>
        </xsl:attribute>
      </xsl:if>
      <xsl:if test="/*/@type and normalize-space(/*/@xml:id) != ''">
        <alix:field name="bookid" type="category" value="{normalize-space(/*/@xml:id)}"/>
      </xsl:if>
      <xsl:choose>
        <xsl:when test="@cert and normalize-space(@cert) != ''">
          <alix:field name="cert" type="category" value="{normalize-space(@cert)}"/>
        </xsl:when>
        <xsl:when test="/*/@cert and normalize-space(/*/@cert) != ''">
          <alix:field name="cert" type="category" value="{normalize-space(/*/@cert)}"/>
        </xsl:when>
      </xsl:choose>
      <xsl:choose>
        <xsl:when test="@type and normalize-space(@type) != ''">
          <alix:field name="type" type="category" value="{normalize-space(@type)}"/>
        </xsl:when>
        <xsl:when test="/*/@type and normalize-space(/*/@type) != ''">
          <alix:field name="type" type="category" value="{normalize-space(/*/@type)}"/>
        </xsl:when>
      </xsl:choose>
      <!-- replication of tags from parent -->
      <xsl:copy-of select="$tags"/>
      <!-- Todo, chapter authors -->
      <xsl:copy-of select="$info"/>
      <alix:field name="toc" type="store">
        <xsl:call-template name="toclocal"/>
      </alix:field>
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
      <alix:field name="bibl" type="meta">
        <xsl:choose>
          <xsl:when test="tei:head/tei:note[@type = 'bibl']">
            <xsl:apply-templates select="tei:head/tei:note[@type = 'bibl']/node()"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:copy-of select="$bibl-book"/>
            <xsl:variable name="analytic">
              <xsl:call-template name="analytic"/>
            </xsl:variable>
            <xsl:if test="$analytic != ''">
              <xsl:text> « </xsl:text>
              <span class="analytic">
                <xsl:copy-of select="$analytic"/>
              </span>
              <xsl:text> »</xsl:text>
            </xsl:if>
          </xsl:otherwise>
        </xsl:choose>
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
      <!-- get the internal links -->
      <xsl:call-template name="links"/>
    </alix:chapter>
  </xsl:template>
  <!-- links supposed internal -->
  <xsl:template name="links">
    <xsl:variable name="links">
      <xsl:for-each select=".//tei:ref">
        <xsl:sort select="@target"/>
        <xsl:variable name="target" select="normalize-space(@target)"/>
        <xsl:choose>
          <xsl:when test="$target = ''"/>
          <xsl:when test="starts-with($target, 'http')"/>
          <xsl:otherwise>
            <alix:field name="link" type="facet" value="{$target}"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </xsl:variable>
    <xsl:for-each select="exslt:node-set($links)/*">
      <xsl:variable name="value" select="@value"/>
      <xsl:choose>
        <xsl:when test="preceding-sibling::*[@value = $value]"/>
        <xsl:otherwise>
          <xsl:copy-of select="."/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
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
