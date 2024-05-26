<?xml version="1.0" encoding="UTF-8"?>
<!--

Part of Teinte https://github.com/oeuvres/teinte
BSD-3-Clause https://opensource.org/licenses/BSD-3-Clause
© 2013 Frederic.Glorieux@fictif.org & LABEX OBVIL
© 2012 Frederic.Glorieux@fictif.org 
© 2010 Frederic.Glorieux@fictif.org & École nationale des chartes
© 2007 Frederic.Glorieux@fictif.org
© 2005 ajlsm.com (Cybertheses)


Different templates shared among the callers
 * metadata
 * internal identifiers (<template name="href">)
 * cross linkink in same file (<template name="id">)
 * mode for links

-->
<xsl:transform  version="1.0"
  xmlns="http://www.w3.org/1999/xhtml" 
  xmlns:date="http://exslt.org/dates-and-times"
  xmlns:exslt="http://exslt.org/common"
  xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" 
  xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#" 
  xmlns:saxon="http://icl.com/saxon" 
  xmlns:tei="http://www.tei-c.org/ns/1.0" 
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  
  exclude-result-prefixes="rdf rdfs tei" 
  extension-element-prefixes="exslt saxon date" 
  >
  <!-- 
Gobal TEI parameters and variables are divided in different categories
 * parameters set exclusively by the caller, impossible to guess from TEI file
 * parameters with prefered values from TEI file but possible to override when calling
 * constant variables
    -->
  <!-- base href for generated links -->
  <xsl:param name="base"/>
  <!-- Allow to change the extension of generated links -->
  <xsl:param name="_ext">.html</xsl:param>
  <!-- Corpus name passed by caller, used as a body class -->
  <xsl:param name="corpusid"/>
  <!-- If true, output processing instructions for a text indexer consumer -->
  <xsl:param name="index"/>
  <!-- Maybe set by a parent transformation, used here for link resolution -->
  <xsl:param name="mode"/>
  <!-- Path from XML file to xsl applied, useful for browser transformation -->
  <xsl:param name="xslbase">
    <xsl:call-template name="xslbase"/>
  </xsl:param>
  <!-- Generation date, maybe modified by caller -->
  <xsl:param name="date">
    <xsl:choose>
      <xsl:when test="function-available('date:date-time')">
        <xsl:variable name="date">
          <xsl:value-of select="date:date-time()"/>
        </xsl:variable>
        <xsl:choose>
          <xsl:when test="contains($date, '+')">
            <xsl:value-of select="substring-before($date, '+')"/>
          </xsl:when>
          <xsl:when test="contains($date, '-')">
            <xsl:value-of select="substring-before($date, '-')"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:value-of select="$date"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
      <xsl:otherwise/>
    </xsl:choose>
  </xsl:param>
  <!-- Name of file, from the caller -->
  <xsl:param name="filename"/>
  <!-- doc name -->
  <xsl:param name="docid">
    <xsl:choose>
      <!-- filename -->
      <xsl:when test="$filename != ''">
        <xsl:value-of select="$filename"/>
      </xsl:when>
      <xsl:when test="/*/@xml:id != ''">
        <xsl:value-of select="/*/@xml:id"/>
      </xsl:when>
      <xsl:when test="/*/@xml:base != ''">
        <xsl:value-of select="/*/@xml:base"/>
      </xsl:when>
      <xsl:when test="/*/@n != '' and /*/@n != '0'">
        <xsl:value-of select="/*/@n"/>
      </xsl:when>
      <!-- try to generate a significant code from author title -->
      <xsl:otherwise>
        <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:author[1]">
          <xsl:variable name="author">
            <xsl:choose>
              <xsl:when test="@key != ''">
                <xsl:value-of select="substring-before(concat(substring-before(concat(@key,','), ','), '('), '(')"/>
              </xsl:when>
              <xsl:when test="contains(., ' ')">
                <xsl:value-of select="substring-before(., ' ')"/>
              </xsl:when>
              <xsl:otherwise>
                <xsl:value-of select="."/>
              </xsl:otherwise>
            </xsl:choose>
          </xsl:variable>
          <xsl:value-of select="translate(normalize-space($author), $idfrom, $idto)"/>
          <xsl:text>_</xsl:text>
        </xsl:for-each>
        <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:title[1]">
          <xsl:variable name="mot1">
            <xsl:value-of select="translate(normalize-space(substring-before(concat(.,' '), ' ')), $idfrom, $idto)"/>
          </xsl:variable>
          <xsl:variable name="mot2">
            <xsl:value-of select="
              translate(
                normalize-space(
                  substring-before(
                    concat(substring-after(., ' '),' '), 
                  ' ')
                ), 
              $idfrom, $idto)"/>
          </xsl:variable>
          <xsl:choose>
            <xsl:when test="contains(' le la les un une des de ', concat(' ',$mot1,' '))">
              <xsl:value-of select="$mot2"/>
            </xsl:when>
            <xsl:otherwise>
              <xsl:value-of select="$mot1"/>
            </xsl:otherwise>
          </xsl:choose>
        </xsl:for-each>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:param>
  <!-- Reference title -->
  <xsl:param name="doctitle">
    <xsl:call-template name="doctitle"/>
  </xsl:param>
  <xsl:template name="doctitle">
    <xsl:choose>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:title">
        <xsl:for-each select="/*/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:title[not(@type) or @type='main']">
          <xsl:if test="position()!=1">. </xsl:if>
          <xsl:apply-templates mode="title"/>
        </xsl:for-each>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblFull/tei:title">
        <xsl:apply-templates mode="title" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblFull/tei:title[1]/node()"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl/tei:title">
        <xsl:apply-templates mode="title" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl/tei:title[1]/node()"/>
      </xsl:when>
      <xsl:when test="/*/tei:text/tei:group/tei:head">
        <xsl:apply-templates mode="title" select="/*/tei:text/tei:group/tei:head[1]/node()"/>
      </xsl:when>
      <xsl:when test="/*/tei:text/tei:body/tei:head">
        <xsl:apply-templates mode="title" select="/*/tei:text/tei:body/tei:head[1]/node()"/>
      </xsl:when>
      <xsl:when test="$docid != ''">
        <xsl:copy-of select="$docid"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="message">
          <xsl:with-param name="id">notitle</xsl:with-param>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- The first author -->
  <xsl:param name="author1">
    <xsl:call-template name="author1"/>
  </xsl:param>
  <xsl:template name="author1">
    <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt[1]">
      <xsl:choose>
        <xsl:when test="tei:principal">
          <xsl:apply-templates select="tei:principal[1]" mode="key"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:apply-templates select="tei:author[1]" mode="key"/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:template>
  <!-- A byline with multiple authors -->
  <xsl:param name="byline">
    <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt[1]">
      <xsl:variable name="names" select="tei:author|tei:principal"/>
      <xsl:choose>
        <xsl:when test="not($names)"/>
        <xsl:when test="count($names) &gt; 3">
            <xsl:for-each select="$names[1]">
              <span class="persName">
                <xsl:call-template name="key"/>
              </span>
            </xsl:for-each>
            <xsl:text> </xsl:text>
            <i>et al.</i>
        </xsl:when>
        <xsl:otherwise>
          <xsl:for-each select="$names">
            <xsl:if test="position() &gt; 1"> ; </xsl:if>
            <xsl:variable name="html">
              <xsl:call-template name="key"/>
            </xsl:variable>
            <span class="persName">
              <xsl:copy-of select="$html"/>
            </span>
            <xsl:variable name="norm" select="normalize-space($html)"/>
            <xsl:variable name="last" select="substring($norm, string-length($norm))"/>
            <xsl:if test="position() = last() and $last != '.'">.</xsl:if>
          </xsl:for-each>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:param>
  <!-- Referenced date -->
  <xsl:param name="docdate">
    <xsl:call-template name="docdate"/>
  </xsl:param>
  <xsl:template name="docdate">
    <xsl:choose>
      <xsl:when test="/*/tei:teiHeader/tei:profileDesc/tei:creation/tei:date[concat(.,@when,@notBefore,@notAfter,@from,@to)!='']">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:profileDesc/tei:creation[1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblFull/tei:publicationStmt/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblFull[1]/tei:publicationStmt[1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type='struct']/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type='struct'][1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:date[1]"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  <!-- Publication date -->
  <xsl:variable name="issued">
    <xsl:choose>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblFull/tei:publicationStmt/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblFull[1]/tei:publicationStmt[1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type='struct']/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type='struct'][1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[1]/tei:date[1]"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:date">
        <xsl:apply-templates mode="year" select="/*/tei:teiHeader/tei:fileDesc/tei:publicationStmt[1]/tei:date[1]"/>
      </xsl:when>
    </xsl:choose>
  </xsl:variable>
  <!-- Identifier string (ISBN, URI…) -->
  <xsl:param name="identifier">
    <xsl:choose>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:idno">
        <xsl:value-of select="/*/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:idno"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type='struct']/tei:idno">
        <xsl:value-of select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:bibl[@type='struct']/tei:idno"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/*/tei:idno">
        <xsl:value-of select="/*/tei:teiHeader/tei:fileDesc/tei:sourceDesc/*/tei:idno"/>
      </xsl:when>
      <xsl:when test="$docid">
        <xsl:value-of select="$docid"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:message>Identifier ?</xsl:message>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:param>
  <!-- Document language (will also be used to choose generated messages) -->
  <xsl:param name="lang">
    <xsl:choose>
      <xsl:when test="/*/@xml:lang">
        <xsl:value-of select="/*/@xml:lang"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:profileDesc/tei:langUsage/@xml:lang">
        <xsl:value-of select="/*/tei:teiHeader/tei:profileDesc/tei:langUsage/@xml:lang"/>
      </xsl:when>
      <xsl:when test="/*/tei:teiHeader/tei:profileDesc/tei:langUsage/tei:language/@ident">
        <xsl:value-of select="/*/tei:teiHeader/tei:profileDesc/tei:langUsage/tei:language/@ident"/>
      </xsl:when>
      <xsl:otherwise>fr</xsl:otherwise>
    </xsl:choose>
  </xsl:param>
  <!-- File for generated messages -->
  <xsl:param name="messages">tei.rdfs</xsl:param>
  <!--  Load messages, document('') works to resolve relative paths  -->
  <xsl:variable name="rdf:Property" select="document($messages)/*/rdf:Property"/>
  <!-- A separate page for footnotes (used by epub) -->
  <xsl:param name="fnpage"/>
  <!-- A dest folder for graphics (used by epub) -->
  <xsl:param name="images"/>
  <!-- For link resolution in href template, split mode with different dest file -->
  <xsl:variable name="split"/>
  <!-- Space separated list of elements names, unique in a TEI document, used for labels -->
  <xsl:variable name="els-unique"> editorialDecl licence projectDesc revisionDesc samplingDecl sourceDesc TEI teiHeader </xsl:variable>
  <!-- A bar of non breaking spaces, used for indentation -->
  <xsl:variable name="nbsp">                                                                                         </xsl:variable>
  <xsl:variable name="cr">
    <xsl:text>&#13;</xsl:text>
  </xsl:variable>
  <xsl:variable name="lf">
    <xsl:text>&#10;</xsl:text>
  </xsl:variable>
  <xsl:variable name="tab">
    <xsl:text>&#9;</xsl:text>
  </xsl:variable>
  <xsl:variable name="apos">"</xsl:variable> 
  <!-- Some constants -->
  <xsl:variable name="html5">html5</xsl:variable>
  <xsl:variable name="notblock"> anchor cb figure graphic index lb milestone pb </xsl:variable> 
  <!-- What kind of root element to output ? html, nav… -->
  <xsl:param name="root" select="$html"/>
  <xsl:variable name="html">html</xsl:variable>
  <xsl:variable name="article">article</xsl:variable>
  <xsl:variable name="nav">nav</xsl:variable>
  <xsl:variable name="ul">ul</xsl:variable>
  <xsl:variable name="ol">ol</xsl:variable>
  <xsl:variable name="front">front</xsl:variable>
  <xsl:variable name="back">back</xsl:variable>
  <!-- Upper case letters with diactitics, translate("L'État", $uc, $lc) = "l'état" -->
  <xsl:variable name="uc">ABCDEFGHIJKLMNOPQRSTUVWXYZÆŒÇÀÁÂÃÄÅÈÉÊËÌÍÎÏÒÓÔÕÖÙÚÛÜÝ</xsl:variable>
  <!-- Lower case letters with diacritics, for translate() -->
  <xsl:variable name="lc">abcdefghijklmnopqrstuvwxyzæœçàáâãäåèéêëìíîïòóôõöùúûüý</xsl:variable>
  <!-- To produce a normalised id without diacritics translate("Déjà vu, 4", $idfrom, $idto) = "dejavu4"  To produce a normalised id -->
  <xsl:variable name="idfrom">ABCDEFGHIJKLMNOPQRSTUVWXYZÀÂÄÉÈÊÏÎÔÖÛÜÇàâäéèêëïîöôüû_ ,.'’ #</xsl:variable>
  <xsl:variable name="idto"  >abcdefghijklmnopqrstuvwxyzaaaeeeiioouucaaaeeeeiioouu_</xsl:variable>
  <!-- Lower case without diacritics -->
  <!-- A normalized bibliographic reference -->
  <xsl:variable name="bibl">
    <xsl:if test="$byline != ''">
      <span class="byline">
        <xsl:copy-of select="$byline"/>
      </span>
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
    <xsl:if test="$doctitle != ''">
      <xsl:text> </xsl:text>
      <em class="title">
        <xsl:copy-of select="$doctitle"/>
      </em>
    </xsl:if>
  </xsl:variable>
  
  <!-- A key to handle identified elements -->
  <xsl:key match="*" name="id" use="@xml:id"/>
  <!-- A key to count elements by name -->
  <xsl:key match="*" name="qname" use="local-name()"/>
  <!-- Put a local @xml:lang attribute according to the  -->
  <xsl:template name="att-lang">
    <xsl:variable name="lang-loc" select="ancestor-or-self::*[@xml:lang][1]/@xml:lang"/>
    <xsl:choose>
      <xsl:when test="$lang-loc != ''">
        <xsl:attribute name="lang">
          <xsl:value-of select="$lang-loc"/>
        </xsl:attribute>
      </xsl:when>
      <xsl:when test="$lang != ''">
        <xsl:attribute name="lang">
          <xsl:value-of select="$lang"/>
        </xsl:attribute>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  <!-- Get a year from a date tag with different possible attributes (TODO, negative dates) -->
  <xsl:template match="*" mode="year" name="year">
    <xsl:choose>
      <xsl:when test="@when">
        <xsl:value-of select="substring(@when, 1, 4)"/>
      </xsl:when>
      <xsl:when test="@to">
        <xsl:value-of select="substring(@to, 1, 4)"/>
      </xsl:when>
      <xsl:when test="@notAfter">
        <xsl:value-of select="substring(@notAfter, 1, 4)"/>
      </xsl:when>
      <xsl:when test="@notBefore">
        <xsl:value-of select="substring(@notBefore, 1, 4)"/>
      </xsl:when>
      <xsl:when test="@to">
        <xsl:value-of select="substring(@to, 1, 4)"/>
      </xsl:when>
      <xsl:when test="@n">
        <xsl:value-of select="substring(@n, 1, 4)"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="text" select="normalize-space(string(.))"/>
        <!-- Try to find a year -->
        <xsl:variable name="XXXX" select="translate($text,'0123456789', '##########')"/>
        <xsl:choose>
          <xsl:when test="contains($XXXX, '####')">
            <xsl:variable name="pos" select="string-length(substring-before($XXXX,'####')) + 1"/>
            <xsl:value-of select="substring($text, $pos, 4)"/>
          </xsl:when>
        </xsl:choose>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template name="key" match="*" mode="key">
    <xsl:variable name="string">
      <xsl:choose>
        <xsl:when test="@key and normalize-space(@key) != ''">
          <xsl:value-of select="normalize-space(@key)"/>
        </xsl:when>
        <xsl:otherwise>
          <!-- process content to strip notes (but keep typo) -->
          <xsl:apply-templates mode="title"/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <!-- Maybe a value in the form : Surname, Firstname (birth, death) -->
    <xsl:variable name="name" select="normalize-space(substring-before(concat(translate($string, ' ', ' '), '('), '('))"/>
    <xsl:choose>
      <!-- Name,  -->
      <xsl:when test="contains($name, ',')">
        <span class="surname">
          <xsl:value-of select="normalize-space(substring-before($name, ','))"/>
        </span>
        <xsl:text>, </xsl:text>
        <xsl:value-of select="normalize-space(substring-after($name, ','))"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:copy-of select="$name"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Produce a hierarchical title for an /html/head/title -->
  <xsl:template name="titlebranch">
    <xsl:variable name="titlebranch">
      <xsl:for-each select="ancestor-or-self::*">
        <xsl:sort order="descending" select="position()"/>
        <xsl:variable name="branch">
          <xsl:apply-templates mode="title" select="."/>
        </xsl:variable>
        <xsl:variable name="branchNorm" select="normalize-space($branch)"/>
        <xsl:choose>
          <xsl:when test="self::tei:TEI"/>
          <xsl:when test="self::tei:text">
            <xsl:if test="position() != 1"> — </xsl:if>
            <xsl:choose>
              <xsl:when test="$bibl">
                <xsl:copy-of select="$bibl"/>
              </xsl:when>
              <xsl:otherwise>
                <xsl:copy-of select="$doctitle"/>
              </xsl:otherwise>
            </xsl:choose>
          </xsl:when>
          <xsl:when test="self::tei:body"/>
          <!--
          <xsl:when test="starts-with($branchNorm, '[')"/>
          -->
          <!-- end -->
          <xsl:otherwise>
            <xsl:if test="position() != 1"> — </xsl:if>
            <xsl:copy-of select="$branch"/>
            <!--
            <xsl:choose>
              <xsl:when test="contains(';.,', substring($branchNorm, string-length($branchNorm)) )"> </xsl:when>
              <xsl:when test="position() = last()">.</xsl:when>
              <xsl:otherwise>. </xsl:otherwise>
            </xsl:choose>
            -->
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </xsl:variable>
    <xsl:value-of select="normalize-space($titlebranch)"/>
  </xsl:template>
  
  <!-- 
    Is given an element and defines whether or not this element is to be rendered inline. 
    XSLT1 can only return string (no booleans)
    
    $inline != '' => is inline
    $inline = '' => is block
    
    BAD tests 
    ($inline = true()) => always true
    not($inline) => always false

  -->
  <xsl:template name="tei:isInline">
    <xsl:param name="element" select="."/>
    <xsl:choose>
      <xsl:when test="count($element) = 0">true</xsl:when>
      <xsl:otherwise>
        <xsl:for-each select="$element">
          <xsl:choose>
            <!-- mixed content -->
            <xsl:when test="../text()[normalize-space(.) != '']">true</xsl:when>
            <!-- Children of <div>, block -->
            <xsl:when test="parent::tei:div"/>
            <!-- Brother of a block, block -->
            <xsl:when test="../tei:p|../tei:l|../tei:lg|../tei:list|../tei:table"/>
            <xsl:when test="parent::tei:cell and self::tei:quote"/>
            <xsl:when test="not(self::tei:note) and (ancestor::tei:p | ancestor::tei:l | ancestor::tei:bibl)">true</xsl:when>
            <xsl:when test="parent::tei:titlePage"/>
            <xsl:when test="parent::tei:body"/>
            <xsl:when test="parent::tei:front"/>
            <xsl:when test="parent::tei:back"/>
            <xsl:when test="parent::tei:list"/>
            <xsl:when test="parent::tei:figure"/>
            <xsl:when test="self::tei:body"/>
            <xsl:when test="self::tei:front"/>
            <xsl:when test="self::tei:back"/>
            <xsl:when test="not(self::*)">true</xsl:when>
            <xsl:when test="parent::tei:bibl/parent::tei:q">true</xsl:when>
            <xsl:when test="contains(@rend,'inline') and not(tei:p or tei:l)">true</xsl:when>
            <xsl:when test="self::tei:note[@place='display']"/>
            <xsl:when test="self::tei:note[@place='block']"/>
            <xsl:when test="self::tei:note">true</xsl:when>
            <xsl:when test="contains(@rend,'display') or contains(@rend,'block')"/>
            <xsl:when test="@type='display' or @type='block'"/>
            <xsl:when test="tei:table or tei:figure or tei:list or tei:lg    or tei:q/tei:l or tei:l or tei:p or tei:biblStruct or tei:sp or tei:floatingText"/>
            <xsl:when test="self::tei:cit[not(@rend)]">true</xsl:when>
            <xsl:when test="parent::tei:cit[contains(@rend,'display')]"/>
            <xsl:when test="parent::tei:cit and (tei:p or tei:l)"/>
            <xsl:when test="parent::tei:cit and parent::cit/tei:bibl"/>
            <xsl:when test="self::tei:docAuthor and parent::tei:byline">true</xsl:when>
            <xsl:when test="self::tei:note[tei:cit/tei:bibl]"/>
            <xsl:when test="self::tei:note[parent::tei:biblStruct]">true</xsl:when>
            <xsl:when test="self::tei:note[parent::tei:bibl]">true</xsl:when>
            <xsl:when test="self::tei:note">true</xsl:when>
            <xsl:when test="self::tei:abbr">true</xsl:when>
            <xsl:when test="self::tei:affiliation">true</xsl:when>
            <xsl:when test="self::tei:altIdentifier">true</xsl:when>
            <xsl:when test="self::tei:analytic">true</xsl:when>
            <xsl:when test="self::tei:add">true</xsl:when>
            <xsl:when test="self::tei:am">true</xsl:when>
            <xsl:when test="self::tei:att">true</xsl:when>
            <xsl:when test="self::tei:author">true</xsl:when>
            <xsl:when test="self::tei:bibl and not(parent::tei:listBibl)">true</xsl:when>
            <xsl:when test="self::tei:biblScope">true</xsl:when>
            <xsl:when test="self::tei:br">true</xsl:when>
            <xsl:when test="self::tei:byline">true</xsl:when>
            <xsl:when test="self::tei:c">true</xsl:when>
            <xsl:when test="self::tei:caesura">true</xsl:when>
            <xsl:when test="self::tei:choice">true</xsl:when>
            <xsl:when test="self::tei:code">true</xsl:when>
            <xsl:when test="self::tei:collection">true</xsl:when>
            <xsl:when test="self::tei:country">true</xsl:when>
            <xsl:when test="self::tei:damage">true</xsl:when>
            <xsl:when test="self::tei:date">true</xsl:when>
            <xsl:when test="self::tei:del">true</xsl:when>
            <xsl:when test="self::tei:depth">true</xsl:when>
            <xsl:when test="self::tei:dim">true</xsl:when>
            <xsl:when test="self::tei:dimensions">true</xsl:when>
            <xsl:when test="self::tei:editor">true</xsl:when>
            <xsl:when test="self::tei:editionStmt">true</xsl:when>
            <xsl:when test="self::tei:emph">true</xsl:when>
            <xsl:when test="self::tei:ex">true</xsl:when>
            <xsl:when test="self::tei:expan">true</xsl:when>
            <xsl:when test="self::tei:figure[@place='inline']">true</xsl:when>
            <xsl:when test="self::tei:figure"/>
            <xsl:when test="self::tei:floatingText"/>
            <xsl:when test="self::tei:foreign">true</xsl:when>
            <xsl:when test="self::tei:forename">true</xsl:when>
            <xsl:when test="self::tei:g">true</xsl:when>
            <xsl:when test="self::tei:gap">true</xsl:when>
            <xsl:when test="self::tei:genName">true</xsl:when>
            <xsl:when test="self::tei:geogName">true</xsl:when>
            <xsl:when test="self::tei:gi">true</xsl:when>
            <xsl:when test="self::tei:gloss">true</xsl:when>
            <xsl:when test="self::tei:graphic">true</xsl:when>
            <xsl:when test="self::tei:media">true</xsl:when>
            <xsl:when test="self::tei:height">true</xsl:when>
            <xsl:when test="self::tei:ident">true</xsl:when>
            <xsl:when test="self::tei:idno">true</xsl:when>
            <xsl:when test="self::tei:imprint">true</xsl:when>
            <xsl:when test="self::tei:institution">true</xsl:when>
            <xsl:when test="self::tei:label[parent::tei:list]"/>
            <xsl:when test="self::tei:label">true</xsl:when>
            <xsl:when test="self::tei:locus">true</xsl:when>
            <xsl:when test="self::tei:mentioned">true</xsl:when>
            <xsl:when test="self::tei:monogr">true</xsl:when>
            <xsl:when test="self::tei:series">true</xsl:when>
            <xsl:when test="self::tei:msName">true</xsl:when>
            <xsl:when test="self::tei:name">true</xsl:when>
            <xsl:when test="self::tei:num">true</xsl:when>
            <xsl:when test="self::tei:orgName">true</xsl:when>
            <xsl:when test="self::tei:orig">true</xsl:when>
            <xsl:when test="self::tei:origDate">true</xsl:when>
            <xsl:when test="self::tei:origPlace">true</xsl:when>
            <xsl:when test="self::tei:pc">true</xsl:when>
            <xsl:when test="self::tei:persName">true</xsl:when>
            <xsl:when test="self::tei:placeName">true</xsl:when>
            <xsl:when test="self::tei:ptr">true</xsl:when>
            <xsl:when test="self::tei:publisher">true</xsl:when>
            <xsl:when test="self::tei:pubPlace">true</xsl:when>
            <xsl:when test="self::tei:lb or self::pb">true</xsl:when>
            <xsl:when test="self::tei:quote and tei:lb"/>
            <xsl:when test="self::tei:q">true</xsl:when>
            <xsl:when test="self::tei:quote">true</xsl:when>
            <xsl:when test="self::tei:ref">true</xsl:when>
            <xsl:when test="self::tei:region">true</xsl:when>
            <xsl:when test="self::tei:repository">true</xsl:when>
            <xsl:when test="self::tei:roleName">true</xsl:when>
            <xsl:when test="self::tei:rubric">true</xsl:when>
            <xsl:when test="self::tei:rs">true</xsl:when>
            <xsl:when test="self::tei:said">true</xsl:when>
            <xsl:when test="self::tei:seg">true</xsl:when>
            <xsl:when test="self::tei:sic">true</xsl:when>
            <xsl:when test="self::tei:settlement">true</xsl:when>
            <xsl:when test="self::tei:soCalled">true</xsl:when>
            <xsl:when test="self::tei:summary">true</xsl:when>
            <xsl:when test="self::tei:supplied">true</xsl:when>
            <xsl:when test="self::tei:surname">true</xsl:when>
            <xsl:when test="self::tei:tag">true</xsl:when>
            <xsl:when test="self::tei:term">true</xsl:when>
            <xsl:when test="self::tei:textLang">true</xsl:when>
            <xsl:when test="self::tei:title">true</xsl:when>
            <xsl:when test="self::tei:unclear">true</xsl:when>
            <xsl:when test="self::tei:val">true</xsl:when>
            <xsl:when test="self::tei:width">true</xsl:when>
            <xsl:when test="self::tei:dynamicContent">true</xsl:when>
            <!-- 
          <xsl:when test="parent::tei:note[tei:isEndNote(.)]"/>
          -->
            <xsl:when test="not(self::tei:p)">
              <xsl:call-template name="tei:isInline">
                <xsl:with-param name="element" select=".."/>
              </xsl:call-template>
            </xsl:when>
          </xsl:choose>
        </xsl:for-each>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  
  
  <!-- A template to get a descent bibliographic to display -->
  <xsl:template name="bibl">
    <xsl:param name="book" select="$bibl"/>
    <xsl:copy-of select="$book"/>
    <xsl:variable name="pages">
      <xsl:call-template name="pages"/>
    </xsl:variable>
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
    <xsl:if test="$pages != ''">
      <xsl:text> </xsl:text>
      <span class="pages">
        <xsl:value-of select="$pages"/>
      </span>
      <xsl:text> </xsl:text>
    </xsl:if>
  </xsl:template>
  
  <xsl:template name="analytic">
    <xsl:for-each select="ancestor-or-self::*[not(self::tei:TEI)][not(self::tei:text)][not(self::tei:body)]">
      <xsl:if test="position() != 1"> — </xsl:if>
      <xsl:apply-templates select="." mode="title"/>
    </xsl:for-each>
  </xsl:template>

  <xsl:template name="pages">
    <xsl:variable name="pb" select=".//tei:pb"/>
    <xsl:if test="$pb">
      <xsl:choose>
        <xsl:when test="count($pb) &gt; 1">p. </xsl:when>
        <xsl:otherwise>p. </xsl:otherwise>
      </xsl:choose>
      <xsl:value-of select="$pb[1]/@n"/>
      <xsl:variable name="last" select="$pb[position() != 1][position() = last()]/@n"/>
      <xsl:if test="$last &gt; 1">
        <xsl:text>-</xsl:text>
        <xsl:value-of select="$last"/>
      </xsl:if>
    </xsl:if>
  </xsl:template>

  <xsl:template name="id">
    <xsl:apply-templates select="." mode="id"/>
  </xsl:template>
    
  <xsl:template match="tei:persName" mode="id">
    <xsl:variable name="id0"> '":,; /\</xsl:variable>
   <xsl:choose>
      <xsl:when test="@xml:id">
        <xsl:value-of select="translate(@xml:id, $id0, '')"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:text>pers</xsl:text>
          <xsl:number level="any"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Shared template to get an id -->
  <xsl:template match="*" mode="id">
    <xsl:param name="prefix"/>
    <!-- Idea for a prefix ?
    <xsl:if test="$docid != ''">
      <xsl:value-of select="translate($docid, $id0, $id1)"/>
      <xsl:text>_</xsl:text>
    </xsl:if>
    -->
    <xsl:param name="suffix"/>
    <xsl:variable name="id0"> '":,; /\</xsl:variable>
    <xsl:value-of select="$prefix"/>
    <xsl:choose>
      <xsl:when test="@xml:id">
        <xsl:value-of select="translate(@xml:id, $id0, '')"/>
      </xsl:when>
      <xsl:when test="@id">
        <xsl:value-of select="translate(@id, $id0, '')"/>
      </xsl:when>
      <xsl:when test="/tei:TEI/tei:text/tei:front and count(.|/tei:TEI/tei:text/tei:front)=1">front</xsl:when>
      <xsl:when test="@type = 'act'">
        <xsl:number format="I" count="tei:*[@type='act']"/>
      </xsl:when>
      <xsl:when test="@type = 'scene'">
        <xsl:for-each select="parent::*[1]">
          <xsl:call-template name="id"/>
        </xsl:for-each>
        <xsl:number format="01"/>
      </xsl:when>
      <xsl:when test="self::tei:sp">
        <xsl:for-each select="parent::*[1]">
          <xsl:call-template name="id"/>
        </xsl:for-each>
        <xsl:text>-</xsl:text>
        <xsl:number format="1"/>
      </xsl:when>
      <xsl:when test="parent::*/@type = 'act'">
        <xsl:for-each select="parent::*[1]">
          <xsl:call-template name="id"/>
        </xsl:for-each>
        <xsl:number format="01"/>
      </xsl:when>
      <xsl:when test="self::tei:listPerson and @type = 'configuration'">
        <xsl:text>conf</xsl:text>
        <xsl:number count="tei:listPerson[@type='configuration']" level="any"/>
      </xsl:when>
      <xsl:when test="not(ancestor::tei:group) and (self::tei:div or starts-with(local-name(), 'div'))">
        <xsl:choose>
          <xsl:when test="ancestor::tei:body">s</xsl:when>
          <xsl:when test="ancestor::tei:front">front</xsl:when>
          <xsl:when test="ancestor::tei:back">back</xsl:when>
        </xsl:choose>
        <xsl:number count="tei:div|tei:div1|tei:div2|tei:div3|tei:div4|tei:div5|tei:div6|tei:div7" format="1-1" level="multiple"/>
      </xsl:when>
      <!-- Simple hierarchy for <div>  -->
      <xsl:when test="self::tei:div and not(ancestor::tei:group)">
        <xsl:number count="tei:div" format="1-1" level="multiple"/>
      </xsl:when>
      <!-- index.html page -->
      <xsl:when test="/tei:TEI/tei:text and count(.|/tei:TEI/tei:text)=1">index</xsl:when>
      <!-- root element -->
      <xsl:when test="count(.. | /*) = 1">
        <xsl:value-of select="local-name()"/>
      </xsl:when>
      <!-- Groups of texts -->
      <xsl:when test="self::tei:group">
        <xsl:value-of select="local-name()"/>
        <xsl:number format="-1-1" from="/*/tei:text/tei:group" level="multiple"/>
      </xsl:when>
      <!-- Unique element in document  -->
      <xsl:when test="contains($els-unique, concat(' ', local-name(), ' '))">
        <xsl:value-of select="local-name()"/>
      </xsl:when>
      <xsl:when test="self::tei:pb">
        <xsl:choose>
          <xsl:when test="contains('0123456789IVXDCM', substring(@n,1,1))">
            <xsl:text>pb</xsl:text>
            <xsl:value-of select="normalize-space(translate(@n, $id0, ''))"/>
          </xsl:when>
          <xsl:when test="@n != ''">
            <xsl:value-of select="normalize-space(translate(@n, $id0, ''))"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:text>p</xsl:text>
            <xsl:number level="any"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
      <xsl:when test="self::tei:figure">
        <xsl:text>fig</xsl:text>
        <xsl:number level="any"/>
      </xsl:when>
      <xsl:when test="self::tei:graphic">
        <xsl:text>img</xsl:text>
        <xsl:number level="any"/>
      </xsl:when>
      <!-- Default -->
      <xsl:when test="true()">
        <xsl:value-of select="local-name()"/>
        <xsl:if test="count(key('qname', local-name())) &gt; 1">
          <xsl:number level="any"/>
        </xsl:if>
      </xsl:when>
      <!-- numérotation des notes, préfixées par la section -->
      <xsl:when test="self::tei:note or self::tei:app or self::tei:cit[@n]">
        <xsl:choose>
          <xsl:when test="ancestor::*[@xml:id]">
            <xsl:value-of select="ancestor::*[@xml:id][1]/@xml:id"/>
            <xsl:text>-</xsl:text>
          </xsl:when>
          <xsl:when test="ancestor::tei:text[parent::tei:group]">
            <xsl:for-each select="ancestor::tei:text[1]">
              <xsl:call-template name="n"/>
            </xsl:for-each>
            <xsl:text>-</xsl:text>
          </xsl:when>
          <xsl:when test="ancestor::*[key('split', generate-id())]">
            <xsl:for-each select="ancestor::*[key('split', generate-id())][1]">
              <xsl:call-template name="id"/>
            </xsl:for-each>
            <xsl:text>-</xsl:text>
          </xsl:when>
        </xsl:choose>
        <xsl:text>fn</xsl:text>
        <xsl:variable name="n">
          <xsl:call-template name="n"/>
        </xsl:variable>
        <xsl:value-of select="translate($n, '()-', '')"/>
      </xsl:when>
      <!-- Où ?
      <xsl:when test="@n and ancestor::*[@xml:id][local-name() != local-name(/*)]">
        <xsl:value-of select="ancestor::*[@xml:id][1]/@xml:id"/>
        <xsl:text>-</xsl:text>
        <xsl:value-of select="@n"/>
      </xsl:when>
      -->
      <!-- lien vers une ancre -->
      <xsl:when test="starts-with(@target, '#') and not(contains(@target, ' '))">
        <xsl:value-of select="substring(@target, 2)"/>
      </xsl:when>
      <!-- Mauvaise solution par défaut -->
      <xsl:otherwise>
        <xsl:value-of select="generate-id()"/>
      </xsl:otherwise>
    </xsl:choose>
    <xsl:value-of select="$suffix"/>
  </xsl:template>
  
  <!--
<h3>mode="n" (numéro)</h3>

<p>
Beaucoup de composants d'un texte peuvent être identifiés par un numéro notamment
les différents types de notes (apparat critique, glose historique, philologique…), ou les sections hiérarchiques.
Un tel numéro peut etre très utile pour 
</p>

-->
  <!-- Numéro élément, priorité aux indications de l'auteur (en général) -->
  <xsl:template match="node()" mode="n" name="n">
    <xsl:variable name="id" select="translate((@xml:id | @id), 'abcdefghijklmnopqrstuvwxyz', '')"/>
    <xsl:choose>
      <xsl:when test="@n">
        <xsl:value-of select="@n"/>
      </xsl:when>
      <!-- numérotation hiérarchique des sections -->
      <xsl:when test="self::tei:div">
        <xsl:number format="1-1" level="multiple"/>
      </xsl:when>
      <xsl:when test="self::tei:div0 or self::tei:div1 or self::tei:div2 or self::tei:div3 or self::tei:div4 or self::tei:div5 or self::tei:div6 or self::tei:div7">
        <xsl:number count="tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7" format="1-1" level="multiple"/>
      </xsl:when>
      <xsl:when test="number($id)">
        <xsl:value-of select="$id"/>
      </xsl:when>
      <!-- textes non identifiés (ou numérotés) -->
      <xsl:when test="self::tei:text and ancestor::tei:group">
        <xsl:number from="tei:text/tei:group" level="any"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:number from="/*/tei:text/tei:body | /*/tei:text/tei:front | /*/tei:text/tei:back" level="any"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!--
mode="title"

This mode get a good candidate title for tocs (ex: without notes).
Some simple formatting is preserved as HTML (italics, superscript…).
Could be correct for a text only version in <xsl:value-of select=""/>
  -->
  <xsl:template match="tei:elementSpec" mode="title">
    <xsl:value-of select="@ident"/>
  </xsl:template>
  <xsl:template name="pipe-comma">
    <xsl:param name="text" select="."/>
    <xsl:choose>
      <xsl:when test="normalize-space($text)=''"/>
      <xsl:when test="contains($text, '|')">
        <xsl:value-of select="normalize-space(substring-before($text, '|'))"/>
        <xsl:text>, </xsl:text>
        <xsl:call-template name="pipe-comma">
          <xsl:with-param name="text" select="substring-after($text, '|')"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="normalize-space($text)"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- find a title for a section  -->
  <xsl:template match="tei:back | tei:body | tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7 | tei:front | tei:group | tei:TEI | tei:text | tei:titlePage" mode="title" name="title">
    <!-- Author in a title ? -->
    <!-- Numérotation construite (?) -->
    <xsl:choose>
      <!-- Short title, not displayed -->
      <xsl:when test="tei:index[@indexName='head'][@n != '']">
        <xsl:value-of select="tei:index[@indexName='head'][@n != '']/@n"/>
      </xsl:when>
      <xsl:when test="tei:index/tei:term[@type='head']">
        <xsl:apply-templates mode="title" select="(tei:index/tei:term[@type='head'])[1]"/>
      </xsl:when>
      <!-- title for a titlePage is "Title page" -->
      <xsl:when test="self::tei:titlePage">
        <xsl:call-template name="message"/>
      </xsl:when>
      <xsl:when test="tei:head[not(@type='sub')][not(@type='subtitle')][not(@type='kicker')]">
        <xsl:variable name="byline">
          <xsl:choose>
            <xsl:when test="tei:byline">
              <xsl:apply-templates mode="title" select="tei:byline"/>
            </xsl:when>
            <xsl:when test="tei:index[@indexName='author' or @indexName='creator']">
              <xsl:for-each select="tei:index[@indexName='author' or @indexName='creator']">
                <xsl:choose>
                  <xsl:when test="@n">
                    <xsl:variable name="n" select="translate(@n, '0123456789', '')"/>
                    <xsl:choose>
                      <xsl:when test="$n='unknown'"/>
                      <xsl:when test="contains($n, '|')">
                        <xsl:call-template name="pipe-comma">
                          <xsl:with-param name="text" select="$n"/>
                        </xsl:call-template>
                      </xsl:when>
                      <xsl:otherwise>
                        <xsl:value-of select="normalize-space($n)"/>
                      </xsl:otherwise>
                    </xsl:choose>
                  </xsl:when>
                  <xsl:otherwise>
                    <xsl:apply-templates/>
                  </xsl:otherwise>
                </xsl:choose>
                <xsl:choose>
                  <xsl:when test="position()=1"/>
                  <xsl:when test="position() = last()"/>
                  <xsl:otherwise>, </xsl:otherwise>
                </xsl:choose>
              </xsl:for-each>
            </xsl:when>
          </xsl:choose>
        </xsl:variable>
        <xsl:variable name="title">
          <xsl:choose>
            <xsl:when test="tei:head">
              <!-- allow @type='kicker' for toc -->
              <xsl:for-each select="tei:head[not(@type='sub')][not(@type='subtitle')]">
                <xsl:apply-templates mode="title" select="."/>
                <xsl:if test="position() != last()">
                  <!-- test if title end by ponctuation -->
                  <xsl:variable name="norm" select="normalize-space(.)"/>
                  <xsl:variable name="last" select="substring($norm, string-length($norm))"/>
                  <xsl:choose>
                    <xsl:when test="translate($last, '.;:?!»', '')!=''">. </xsl:when>
                    <xsl:otherwise>
                      <xsl:text> </xsl:text>
                    </xsl:otherwise>
                  </xsl:choose>
                </xsl:if>
              </xsl:for-each>
            </xsl:when>
            <xsl:when test="@n != ''">
              <xsl:value-of select="normalize-space(@n)"/>
            </xsl:when>
          </xsl:choose>
        </xsl:variable>
        <xsl:copy-of select="$title"/>
        <!-- Afficher un Byline en TOC ?
        <xsl:choose>
          <xsl:when test="$byline != ''">
            <xsl:variable name="norm" select="normalize-space($title)"/>
            <xsl:if test="substring($norm, string-length($norm)) != '.'">
              <xsl:text>.</xsl:text>
            </xsl:if>
            <xsl:text> </xsl:text>
            <xsl:copy-of select="$byline"/>
          </xsl:when>
        </xsl:choose>
        -->
      </xsl:when>
      <!-- titlePage is not the title of a front
      <xsl:when test="tei:titlePage">
        <xsl:apply-templates select="(tei:titlePage[1]/tei:docTitle/tei:titlePart|tei:titlePage[1]/tei:titlePart)[1]" mode="title"/>
      </xsl:when>
      -->
      <!-- Front or back with no local title, use a generic label  -->
      <xsl:when test="self::tei:front|self::tei:back">
        <xsl:call-template name="message"/>
      </xsl:when>
      <!-- Level <text>, get a short title to display, an author maybe nice as a prefix -->
      <xsl:when test=" self::tei:text or self::tei:body ">
        <xsl:variable name="title">
          <xsl:choose>
            <xsl:when test="tei:body/tei:head">
              <xsl:apply-templates mode="title" select="tei:body/tei:head[1]"/>
            </xsl:when>
            <xsl:when test="tei:front/tei:head">
              <xsl:apply-templates mode="title" select="tei:front/tei:head[1]"/>
            </xsl:when>
            <xsl:when test="not(ancestor::tei:group)">
              <xsl:copy-of select="$doctitle"/>
            </xsl:when>
            <!-- title for a titlePage is "Title page" -->
            <xsl:when test="tei:front/tei:titlePage">
              <xsl:apply-templates mode="title" select="(.//tei:front/tei:titlePage[1]/tei:docTitle[1]/tei:titlePart[1] | .//tei:front/tei:titlePage[1]/tei:titlePart[1])[1]"/>
            </xsl:when>
          </xsl:choose>
        </xsl:variable>
        <xsl:variable name="author">
          <xsl:choose>
            <!-- search for docDates ? -->
            <xsl:when test="ancestor::tei:group"/>
            <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:author">
              <xsl:for-each select="/*/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:author[1]">
                <xsl:choose>
                  <xsl:when test="contains(@key, '(')">
                    <xsl:value-of select="normalize-space(substring-before(@key, '('))"/>
                  </xsl:when>
                  <xsl:when test="@key">
                    <xsl:value-of select="@key"/>
                  </xsl:when>
                  <xsl:when test="contains(., '(')">
                    <xsl:value-of select="normalize-space(substring-before(., '('))"/>
                  </xsl:when>
                  <xsl:otherwise>
                    <xsl:value-of select="normalize-space(.)"/>
                  </xsl:otherwise>
                </xsl:choose>
              </xsl:for-each>
              <xsl:choose>
                <xsl:when test="/*/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:author[2]">… </xsl:when>
                <xsl:otherwise>. </xsl:otherwise>
              </xsl:choose>
            </xsl:when>
          </xsl:choose>
        </xsl:variable>
        <xsl:choose>
          <!-- Juste title, no author ?
          <xsl:when test="$author != ''">
            <xsl:copy-of select="$author"/>
            <i>
              <xsl:copy-of select="$title"/>
            </i>
          </xsl:when>
          -->
          
          <xsl:when test="$title">
            <xsl:copy-of select="$title"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:call-template name="idpath"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
      <xsl:when test="tei:docTitle">
        <xsl:apply-templates mode="title" select="tei:docTitle[1]/tei:titlePart[1]"/>
      </xsl:when>
      <!-- a date as a title (ex, acte) -->
      <xsl:when test="tei:docDate">
        <xsl:apply-templates mode="title" select="tei:docDate[1]"/>
      </xsl:when>
      <!-- A <text> ? -->
      <xsl:when test="tei:front/tei:titlePage">
        <xsl:apply-templates mode="title" select="tei:front/tei:titlePage"/>
      </xsl:when>
      <!-- /TEI/text ? -->
      <xsl:when test="../tei:teiHeader">
        <xsl:apply-templates mode="title" select="../tei:teiHeader"/>
      </xsl:when>
      <!-- Après front, supposé plus affichable qu'un teiHeader -->
      <xsl:when test="tei:teiHeader">
        <xsl:apply-templates mode="title" select="tei:teiHeader"/>
      </xsl:when>
      <xsl:when test="tei:dateline">
        <xsl:apply-templates mode="title" select="tei:dateline"/>
      </xsl:when>
      <xsl:when test="@n">
        <xsl:value-of select="@n"/>
      </xsl:when>
      <!-- -->
      <!-- bad TEI generated -->
      <xsl:when test="false()">
        <!-- Let’s bet on first non empty block ? be careful of epigraphs, notes or page break… -->
        <xsl:variable name="first" select="*[self::tei:p or self::tei:dateline or self::tei:byline or self::tei:label][1]"/>
        <xsl:variable name="norm" select="normalize-space($first)"/>
        <xsl:variable name="max" select="50"/>
        <xsl:choose>
          <!-- Avoid to big title -->
          <xsl:when test="string-length($norm) &gt; $max">
            <xsl:value-of select="substring($norm, 1, $max)"/>
            <xsl:variable name="depunct" select="translate(substring($norm, $max + 1), ',.: ?!', '             ')"/>
            <xsl:value-of select="substring-before($depunct, ' ')"/>
            <xsl:text>…</xsl:text>
          </xsl:when>
          <xsl:otherwise>
            <xsl:apply-templates select="$first/node()" mode="title"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
      <!--
      <xsl:when test="self::tei:div and parent::tei:body">
        <xsl:text>[</xsl:text>
        <xsl:call-template name="message">
          <xsl:with-param name="id">chapter</xsl:with-param>
        </xsl:call-template>
        <xsl:text> </xsl:text>
        <xsl:call-template name="n"/>
        <xsl:text>]</xsl:text>
      </xsl:when>
      <xsl:otherwise>
        <xsl:text>[</xsl:text>
        <xsl:call-template name="n"/>
        <xsl:text>]</xsl:text>
      </xsl:otherwise>
      -->
      <xsl:otherwise>
        <!--
        <xsl:number count="tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5" level="multiple" format="I.1.a."/>
        -->
        <xsl:number format="I."/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- title, verify if empty -->
  <xsl:template match="tei:head" mode="title">
    <xsl:variable name="html">
      <xsl:apply-templates mode="title"/>
    </xsl:variable>
    <xsl:choose>
      <xsl:when test="$html = ''">
        <xsl:text>[</xsl:text>
        <xsl:for-each select="ancestor::tei:div[1]">
          <xsl:call-template name="n"/>
        </xsl:for-each>
        <xsl:text>]</xsl:text>
      </xsl:when>
      <xsl:otherwise>
        <xsl:copy-of select="$html"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- titre dans un front -->
  <xsl:template match="tei:titlePart" mode="title">
    <xsl:apply-templates mode="title"/>
  </xsl:template>
  <!-- no notes in title mode -->
  <xsl:template match="tei:note | tei:index" mode="title"/>
  <xsl:template match="tei:pb" mode="title">
    <xsl:text> </xsl:text>
  </xsl:template>
  <xsl:template match="text()" mode="title">
    <xsl:variable name="text" select="translate(., '', '')"/>
    <xsl:if test="translate(substring($text, 1,1), concat(' ', $lf, $cr, $tab), '') = ''">
      <xsl:text> </xsl:text>
    </xsl:if>
    <xsl:value-of select="normalize-space($text)"/>
    <xsl:choose>
      <xsl:when test="following-sibling::node()[1][self::tei:lb]"/>
      <!--
      <xsl:when test="ancestor::tei:head and count(.|ancestor::tei:head/node()[position() = last()])"/>
      -->
      <xsl:when test="translate(substring($text, string-length($text)), concat(' ', $lf, $cr, $tab), '') = ''">
        <xsl:text> </xsl:text>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  
  <xsl:template name="lspacer">
    <xsl:if test="@part = 'M' or @part = 'm' or @part = 'F' or @part = 'f'  or @part = 'y'  or @part = 'Y'">
      <!-- Rupted verse, get the exact spacer from previous verse -->
      <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
    </xsl:if>
    
  </xsl:template>
  
  <xsl:template name="head-pun">
    <xsl:param name="prev">
      <xsl:apply-templates/>
    </xsl:param>
    <xsl:param name="next">
      <xsl:value-of select="following-sibling::*[normalize-space(.)!=''][1]"/>
    </xsl:param>
    <xsl:variable name="norm" select="normalize-space($prev)"/>
    <xsl:variable name="lastc" select="substring($norm, string-length($norm))"/>
    <xsl:variable name="nextc" select="substring(normalize-space($next), 1, 1)"/>
    <xsl:choose>
      <xsl:when test="$norm = ''"/>
      <xsl:when test="contains('0123456789', $lastc)">.</xsl:when>
      <xsl:when test="contains('.,; –—-)!?»', $lastc)"/>
      <xsl:when test="contains($lc, $nextc)">,</xsl:when>
      <xsl:otherwise>.</xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  
  <xsl:template match="tei:l" mode="lspacer">
    <xsl:variable name="txt">
      <xsl:apply-templates mode="title"/>
    </xsl:variable>
    <xsl:choose>
      <!-- encoding may have problem for ex @part="F"
      <xsl:when test="not(@part)"/>
      -->
      <xsl:when test="not(ancestor::tei:body)"/>
      <xsl:when test="@part = 'F'">
        <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="@part = 'M'">
        <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
        <xsl:text> </xsl:text>
        <xsl:value-of select="$txt"/>
      </xsl:when>
      <xsl:when test="@part = 'Y'">
        <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="@part = 'I'">
        <xsl:value-of select="$txt"/>
      </xsl:when>
      <!-- No part="I" ? -->
      <xsl:otherwise>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  
  
  <xsl:template match="tei:lb" mode="title">
    <xsl:variable name="prev" select="preceding-sibling::node()[1]"/>
    <xsl:variable name="next" select="following-sibling::node()[1]"/>
    <xsl:variable name="norm" select="normalize-space( $prev )"/>
    <xsl:variable name="lastchar" select="substring($norm, string-length($norm))"/>
    <xsl:variable name="nextchar" select="substring(normalize-space($next), 1, 1)"/>
    <xsl:choose>
      <xsl:when test="contains(',.;:—–-)?!»&quot;', $lastchar)">
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="contains($uc, $nextchar)">
        <xsl:text>. </xsl:text>
      </xsl:when>
      <xsl:when test="not(contains(concat($prev, $next), ','))">
        <xsl:text>, </xsl:text>
      </xsl:when>
      <!-- last char should be a letter and not a space if we append a dot -->
      <xsl:when test="string-length($prev) = string-length($norm)">
        <xsl:text>. </xsl:text>
      </xsl:when>
      <xsl:otherwise>
        <xsl:text>. </xsl:text>
        <!--
        <xsl:text> – </xsl:text>
        -->
      </xsl:otherwise>
    </xsl:choose>
    <xsl:text> </xsl:text>
  </xsl:template>
  <xsl:template match="tei:choice" mode="title">
    <xsl:choose>
      <xsl:when test="tei:reg">
        <xsl:apply-templates mode="title" select="tei:reg/node()"/>
      </xsl:when>
      <xsl:when test="tei:expan">
        <xsl:apply-templates mode="title" select="tei:expan/node()"/>
      </xsl:when>
      <xsl:when test="tei:corr">
        <xsl:apply-templates mode="title" select="tei:corr/node()"/>
      </xsl:when>
      <xsl:when test="tei:ex">
        <xsl:apply-templates mode="title" select="tei:ex/node()"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:apply-templates/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- default, cross all, keep only text -->
  <xsl:template match="*" mode="title" priority="-2">
    <xsl:apply-templates mode="title"/>
  </xsl:template>

  <!-- Keep text from some element with possible values in attributes -->
  <xsl:template match="tei:date | tei:docDate | tei:origDate" mode="title">
    <xsl:variable name="text">
      <xsl:apply-templates select="."/>
    </xsl:variable>
    <xsl:text> </xsl:text>
    <xsl:value-of select="$text"/>
    <xsl:text> </xsl:text>
  </xsl:template>
  <!-- normalization of space in title has lots of bad side effects, do not -->
  <xsl:template match="tei:teiHeader" mode="title">
    <xsl:if test="tei:fileDesc/tei:titleStmt/tei:author">
      <xsl:apply-templates mode="title" select="tei:fileDesc/tei:titleStmt/tei:author[1]"/>
      <xsl:text>. </xsl:text>
    </xsl:if>
    <xsl:apply-templates mode="title" select="tei:fileDesc/tei:titleStmt/tei:title[1]"/>
    <xsl:variable name="date">
      <xsl:apply-templates mode="title" select="tei:profileDesc[1]/tei:creation[1]/tei:date[1]"/>
    </xsl:variable>
    <xsl:if test="normalize-space($date) != ''">
      <xsl:text> (</xsl:text>
      <xsl:value-of select="normalize-space($date)"/>
      <xsl:text>)</xsl:text>
    </xsl:if>
  </xsl:template>
  <!-- Keep some rendering in title TOC -->
  <xsl:template match="tei:hi" mode="title">
    <xsl:choose>
      <xsl:when test=". =''"/>
      <!-- si @rend est un nom d'élément HTML -->
      <xsl:when test="contains( ' b big em i s small strike strong sub sup tt u ', concat(' ', @rend, ' '))">
        <xsl:element name="{@rend}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:if test="@type">
            <xsl:attribute name="class">
              <xsl:value-of select="@type"/>
            </xsl:attribute>
          </xsl:if>
          <xsl:apply-templates mode="title"/>
        </xsl:element>
      </xsl:when>
      <!-- sinon appeler le span général -->
      <xsl:otherwise>
        <span class="{@rend}">
          <xsl:apply-templates mode="title"/>
        </span>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:emph" mode="title">
    <em>
      <xsl:apply-templates mode="title"/>
    </em>
  </xsl:template>
  <xsl:template match="tei:name | tei:num | tei:surname" mode="title">
    <span class="{local-name()}">
      <xsl:apply-templates mode="title"/>
    </span>
  </xsl:template>
  <xsl:template match="tei:title" mode="title">
    <xsl:variable name="cont">
      <xsl:apply-templates mode="title"/>
    </xsl:variable>
    <xsl:choose>
      <xsl:when test="$cont = ''"/>
      <xsl:otherwise>
        <em class="{local-name()}">
          <xsl:copy-of select="$cont"/>
        </em>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Good idea ?  -->
  <!--
  <xsl:template match="tei:num/text() | tei:surname/text()" mode="title">
    <xsl:value-of select="translate(., $mins, $caps)"/>
  </xsl:template>
  -->
  <xsl:template match="*" mode="label">
    <xsl:apply-templates mode="title" select="."/>
  </xsl:template>
  <!-- 
    Centralisation of internal links rewriting, to call when current() node is the target.
    This template will choose if link should stay an anchor or will be rewritten according to the split
    policy.
  -->
  <xsl:template name="href">
    <xsl:param name="base" select="$base"/>
    <xsl:param name="class"/>
    <!-- possible override id -->
    <xsl:param name="id">
      <xsl:call-template name="id"/>
    </xsl:param>
    <xsl:choose>
      <!-- When transform is called from monopage  -->
      <xsl:when test="not($split)">
        <xsl:text>#</xsl:text>
        <xsl:value-of select="$id"/>
      </xsl:when>
      <!-- For a deported page of notes (site or epub) -->
      <xsl:when test="$class = 'noteref' and $fnpage != ''">
        <xsl:value-of select="$base"/>
        <xsl:value-of select="$fnpage"/>
        <xsl:value-of select="$_ext"/>
        <xsl:text>#</xsl:text>
        <xsl:value-of select="$id"/>
      </xsl:when>
      <!-- is a splitted section -->
      <xsl:when test="self::*[key('split', generate-id())]">
        <xsl:value-of select="$base"/>
        <xsl:value-of select="$id"/>
        <xsl:value-of select="$_ext"/>
      </xsl:when>
      <!-- parent of a split section -->
      <xsl:when test="descendant::*[key('split', generate-id())]">
        <xsl:value-of select="$base"/>
        <xsl:value-of select="$id"/>
        <xsl:value-of select="$_ext"/>
      </xsl:when>
      <!-- Child of a split section -->
      <xsl:when test="ancestor::*[key('split', generate-id())]">
        <xsl:value-of select="$base"/>
        <xsl:for-each select="ancestor::*[key('split', generate-id())][1]">
          <xsl:call-template name="id"/>
          <xsl:value-of select="$_ext"/>
        </xsl:for-each>
        <xsl:text>#</xsl:text>
        <xsl:value-of select="$id"/>
      </xsl:when>
      <!-- ??
      <xsl:when test="ancestor-or-self::tei:body[count(.| /*/tei:text/tei:body) = 1]">
        <xsl:value-of select="$base"/>
        <xsl:choose>
          <xsl:when test="$_html = ''">.</xsl:when>
          <xsl:otherwise>index<xsl:value-of select="$_html"/></xsl:otherwise>
        </xsl:choose>
        <xsl:if test="not(self::tei:body)">
          <xsl:text>#</xsl:text>
          <xsl:value-of select="$id"/>
        </xsl:if>
      </xsl:when>
      <xsl:when test="count(../.. | /*) = 1">
        <xsl:value-of select="$base"/>
        <xsl:value-of select="$id"/>
        <xsl:value-of select="$_html"/>
      </xsl:when>
      -->
      <!-- ???? -->
      <xsl:when test="ancestor::tei:*[local-name(../..)='TEI']">
        <xsl:for-each select="ancestor::tei:*[local-name(../..)='TEI'][1]">
          <xsl:call-template name="id"/>
          <xsl:value-of select="$_ext"/>
        </xsl:for-each>
        <xsl:text>#</xsl:text>
        <xsl:value-of select="$id"/>
      </xsl:when>
      <!-- No split, just anchor -->
      <xsl:otherwise>
        <xsl:text>#</xsl:text>
        <xsl:value-of select="$id"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!--
<h3>mode="label" (titre court)</h3>

<p>
Le mode label génère un intitulé court obtenu par une liste de valeurs localisés (./tei.rdfs).
</p>
  -->
  <!-- Message, intitulé court d'un élément TEI lorsque disponible -->
  <xsl:template name="message">
    <xsl:param name="id" select="local-name()"/>
    <xsl:choose>
      <xsl:when test="$rdf:Property[@xml:id = $id]/rdfs:label[starts-with( $lang, @xml:lang)]">
        <xsl:copy-of select="$rdf:Property[@xml:id = $id]/rdfs:label[starts-with( $lang, @xml:lang)][1]/node()"/>
      </xsl:when>
      <xsl:when test="$rdf:Property[@xml:id = $id]/rdfs:label">
        <xsl:copy-of select="$rdf:Property[@xml:id = $id]/rdfs:label[1]/node()"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="$id"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  
  <!-- Is <p> noindent ? -->
  <xsl:template name="noindent">
    <xsl:variable name="rend" select="concat(' ', normalize-space(@rend), ' ')"/>
    <!-- first non space char -->
    <xsl:variable name="firstchar" select="substring(translate(normalize-space(.), ' ', ''), 1, 1)"/>
    <xsl:variable name="firstnode" select="node()[1][normalize-space(.) != '']"/>
    <!-- previous sibling block -->
    <xsl:variable name="prevblock" select="preceding-sibling::*[not(contains($notblock, concat(' ', local-name(), ' ')))][1]"/>
    <!-- previous <p> -->
    <!--
    <xsl:variable name="prevp" select="preceding-sibling::tei:p[1]"/>
    -->
    <xsl:variable name="txt">
      <xsl:apply-templates select="." mode="title"/>
    </xsl:variable>
    <xsl:variable name="len" select="string-length(normalize-space($txt))"/>
    <xsl:choose>
      <!-- force noindent -->
      <xsl:when test="contains($rend, ' noindent ')">noindent</xsl:when>
      <!-- force indent -->
      <xsl:when test="contains($rend, ' indent ')"/>
      <!-- Starting by a label, all the same -->
      <xsl:when test="local-name($firstnode) = 'label'"/>
      <!-- Numbering to display -->
      <xsl:when test="@n != ''">noindent</xsl:when>
      <!-- first char seem a pseudo item, let indent -->
      <xsl:when test="contains('-–—1234567890', $firstchar)"/>
      <!-- theater, first <p> of a series -->
      <xsl:when test="name($prevblock) != 'p' and (parent::tei:sp or parent::tei:quote)">noindent</xsl:when>
      <!-- first <p> of a series, smaller than 2 “line”, let indent -->
      <xsl:when test="not($prevblock) and $len &lt; 80"/> 
      <!-- first <p> of a series (> 2 lines), noindent -->
      <xsl:when test="not($prevblock)">noindent</xsl:when>
      <!-- if preceded by an empty <p> -->
      <xsl:when test="name($prevblock) = 'p' and normalize-space($prevblock)=''">noindent</xsl:when>
      <!-- if preceded by not <p> -->
      <xsl:when test="name($prevblock) != 'p' and not(parent::tei:item)">noindent</xsl:when>
      <!-- this para is center or right align -->
      <xsl:when test="contains($rend, ' center ') or contains($rend, ' right ')">noindent</xsl:when>
      <!-- prev block (maybe <p>) is center or right align -->
      <xsl:when test="contains($prevblock/@rend, 'right')  or contains($prevblock/@rend, 'center')">noindent</xsl:when>
      <!-- bad
      -->
    </xsl:choose>
    
  </xsl:template>
  
  <!-- In case of direct XSLT transformation in browser, get the folder -->
  <xsl:template name="xslbase">
    <xsl:param name="path" select="/processing-instruction('xml-stylesheet')[contains(., '.xsl')]"/>
    <xsl:choose>
      <xsl:when test="contains($path, 'href=&quot;')">
        <xsl:call-template name="xslbase">
          <xsl:with-param name="path" select="substring-after($path, 'href=&quot;')"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:when test="contains($path, '&quot;')">
        <xsl:variable name="p" select="substring-before($path, '&quot;')"/>
        <xsl:choose>
          <xsl:when test="not(contains($p, '/')) and not(contains($p, '\'))">./</xsl:when>
          <xsl:otherwise>
            <xsl:call-template name="xslbase">
              <xsl:with-param name="path" select="$p"/>
            </xsl:call-template>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
      <!-- Absolute, do nothing -->
      <xsl:when test="starts-with($path, 'http')"/>
      <!-- cut beforer quote -->
      <xsl:when test="contains($path, '/')">
        <xsl:value-of select="substring-before($path, '/')"/>
        <xsl:text>/</xsl:text>
        <xsl:call-template name="xslbase">
          <xsl:with-param name="path" select="substring-after($path, '/')"/>
        </xsl:call-template>
      </xsl:when>
      <!-- win centric -->
      <xsl:when test="contains($path, '\')">
        <xsl:value-of select="substring-before($path, '\')"/>
        <xsl:text>/</xsl:text>
        <xsl:call-template name="xslbase">
          <xsl:with-param name="path" select="substring-after($path, '\')"/>
        </xsl:call-template>
      </xsl:when>
    </xsl:choose>
  </xsl:template>

  <!--
    Xslt 1 has no replace function, a workaround
  -->
  <xsl:template name="replace">
    <xsl:param name="text"/>
    <xsl:param name="search"/>
    <xsl:param name="replace"/>
    <xsl:choose>
      <xsl:when test="contains($text,$search)">
        <xsl:value-of select="substring-before($text,$search)"/>
        <xsl:value-of select="$replace"/>
        <xsl:call-template name="replace">
          <xsl:with-param name="text" select="substring-after($text,$search)"/>
          <xsl:with-param name="search" select="$search"/>
          <xsl:with-param name="replace" select="$replace"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="$text"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- attribut de dates en fonctions de la valeur courant,
dégrossi le travail, mais du reste à faire  -->
  <xsl:template name="date">
    <!-- arondir dates inventées -->
    <xsl:choose>
      <!-- déjà des attributs -->
      <xsl:when test="@notBefore and @notAfter and @notAfter=@notBefore">
        <xsl:attribute name="when">
          <xsl:value-of select="@notAfter"/>
        </xsl:attribute>
      </xsl:when>
      <!-- notBefore="1200-09-01" notAfter="1200-09-30" -->
      <xsl:when test="substring(@notBefore, 8, 3) = '-01' and contains('-30-31-28', substring(@notAfter, 8, 3))">
        <xsl:attribute name="when">
          <xsl:value-of select="substring(@notBefore, 1, 7)"/>
        </xsl:attribute>
      </xsl:when>
      <xsl:when test="@notBefore|@notAfter|@when">
        <xsl:copy-of select="@*"/>
      </xsl:when>
      <!-- deux dates, problème -->
      <xsl:when test="contains(., ')(')">
        <!-- xsl:message>Acte <xsl:value-of select="ancestor::text[1]/@n"/>, 2 dates <xsl:value-of select="."/></xsl:message --> </xsl:when>
      <!-- que des chiffres, année ? -->
      <xsl:when test="translate(., '()1234567890.', '') = ''">
        <xsl:attribute name="when">
          <xsl:value-of select="translate(., '(). ', '')"/>
        </xsl:attribute>
      </xsl:when>
      <!-- date simple -->
      <xsl:when test="starts-with(., '(Vers')">
        <xsl:attribute name="when">
          <xsl:value-of select="translate(., '(Vers) ', '')"/>
        </xsl:attribute>
      </xsl:when>
      <!-- date simple -->
      <xsl:when test="starts-with(., '(Avant')">
        <xsl:attribute name="notAfter">
          <xsl:value-of select="translate(., '(Avant) ', '')"/>
        </xsl:attribute>
      </xsl:when>
      <!-- date simple -->
      <xsl:when test="starts-with(., '(Après')">
        <xsl:attribute name="notBefore">
          <xsl:value-of select="translate(., '(Après) ', '')"/>
        </xsl:attribute>
      </xsl:when>
      <!-- période -->
      <xsl:when test="starts-with(., '(Entre')">
        <xsl:attribute name="notBefore">
          <xsl:value-of select="substring-before(substring-after(., 'Entre '), ' ')"/>
        </xsl:attribute>
        <xsl:attribute name="notAfter">
          <xsl:value-of select="translate( substring-before(substring-after(., 'et '), ')') , '(). ', '')"/>
        </xsl:attribute>
      </xsl:when>
      <!-- cas non pris en compte, à remplir ensuite -->
      <xsl:otherwise>
        <!-- xsl:message>Acte <xsl:value-of select="ancestor::text[1]/@n"/>, date non prise en charge <xsl:value-of select="."/></xsl:message --> </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- For debug, a linear xpath for an element -->
  <xsl:template name="idpath">
    <xsl:for-each select="ancestor-or-self::*">
      <xsl:text>/</xsl:text>
      <xsl:value-of select="name()"/>
      <xsl:if test="count(../*[name()=name(current())]) &gt; 1">
        <xsl:text>[</xsl:text>
        <xsl:number/>
        <xsl:text>]</xsl:text>
      </xsl:if>
    </xsl:for-each>
  </xsl:template>
  <!-- Add un final as a separator -->
  <xsl:template name="dot">
    <xsl:param name="current" select="."/>
    <xsl:variable name="lastChar" select="substring($current, string-length($current))"/>
    <xsl:if test="translate($lastChar, '.?!,;:', '') != ''">. </xsl:if>
  </xsl:template>
  <!-- Loop on a space separated list of URIs, to display links -->
  <xsl:template name="anchors" match="node()|@*" mode="anchors">
    <!-- String to loop on -->
    <xsl:param name="anchors" select="."/>
    <!-- First id of the list -->
    <xsl:variable name="id" select="
      substring-before(
      concat($anchors, ' ') , ' '
      )"/>
    <xsl:choose>
      <!-- nothing more, finish -->
      <xsl:when test="normalize-space($anchors) = ''"/>
      <!-- Is not an anchor (maybe a link ?) -->
      <xsl:when test="not(starts-with($id, '#'))">
        <i class="{local-name()}">
          <xsl:value-of select="$id"/>
        </i>
      </xsl:when>
      <!-- anchor inside the document, build the link, according to the split policy -->
      <xsl:when test="key('id', substring-after($id, '#'))">
        <xsl:text> </xsl:text>
        <xsl:apply-templates select="key('id', substring-after($id, '#'))" mode="a"/>
      </xsl:when>
      <!-- anchor not in document -->
      <xsl:otherwise>
        <i class="{local-name()}">
          <xsl:value-of select="substring-after($id, '#')"/>
        </i>
      </xsl:otherwise>
    </xsl:choose>
    <xsl:variable name="next" select="normalize-space(substring-after($anchors, ' '))"/>
    <xsl:if test="$next != ''">
      <xsl:text> </xsl:text>
      <xsl:call-template name="anchors">
        <xsl:with-param name="anchors" select="$next"/>
      </xsl:call-template>
    </xsl:if>
  </xsl:template>
  
</xsl:transform>
