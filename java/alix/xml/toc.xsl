<?xml version="1.0" encoding="UTF-8"?>
<!--
Produce a table of contents from section structure

LGPL  http://www.gnu.org/licenses/lgpl.html
© 2019 Frederic.Glorieux@fictif.org & Opteos & LABEX OBVIL
© 2013 Frederic.Glorieux@fictif.org & LABEX OBVIL
© 2012 Frederic.Glorieux@fictif.org 
© 2010 Frederic.Glorieux@fictif.org & École nationale des chartes
© 2007 Frederic.Glorieux@fictif.org
© 2005 ajlsm.com et Cybertheses


-->
<xsl:transform version="1.0" 
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform" 
  xmlns="http://www.w3.org/1999/xhtml" 
  xmlns:tei="http://www.tei-c.org/ns/1.0"
  exclude-result-prefixes="tei" 
  >
  <xsl:import href="common.xsl"/>
  <xsl:strip-space elements="tei:TEI tei:TEI.2 tei:body tei:castList  tei:div tei:div1 tei:div2  tei:docDate tei:docImprint tei:docTitle tei:fileDesc tei:front tei:group tei:index tei:listWit tei:publicationStmp tei:publicationStmt tei:sourceDesc tei:SourceDesc tei:sources tei:text tei:teiHeader tei:text tei:titleStmt"/>
  <!-- Generate an absolute table of sections -->
  <xsl:template name="toc">
    <xsl:variable name="html">
      <xsl:apply-templates select="/*/tei:text/tei:front" mode="li"/>
      <xsl:apply-templates select="/*/tei:text/tei:body" mode="li"/>
      <xsl:apply-templates select="/*/tei:text/tei:group" mode="li"/>
      <xsl:apply-templates select="/*/tei:text/tei:back" mode="li"/>
    </xsl:variable>
    <xsl:if test="$html != ''">
      <ul class="tree">
        <xsl:copy-of select="$html"/>
      </ul>
    </xsl:if>
  </xsl:template>

  <xsl:template name="toclocal">
    <ul>
      <xsl:apply-templates select="/*/tei:text/tei:front/* | /*/tei:text/tei:body/* | /*/tei:text/tei:group/* | /*/tei:text/tei:back/*" mode="toclocal">
        <xsl:with-param name="localid" select="generate-id()"/>
      </xsl:apply-templates>
    </ul>
  </xsl:template>
  
  
  <!-- List toc entries with a split link -->
  <xsl:template match="*" mode="tocsplit"/>
  <xsl:template match="tei:div" mode="tocsplit">
    <xsl:choose>
      <xsl:when test="descendant::*[key('split', generate-id())]">
        <details>
          <summary>
            <xsl:choose>
              <xsl:when test="key('split', generate-id())">
                <xsl:call-template name="a"/>
              </xsl:when>
              <xsl:otherwise>
                <xsl:call-template name="title"/>
              </xsl:otherwise>
            </xsl:choose>
          </summary>
          <xsl:apply-templates select="*" mode="tocsplit"/>
        </details>
      </xsl:when>
      <xsl:otherwise>
        <div>
          <xsl:choose>
            <xsl:when test="key('split', generate-id())">
              <xsl:call-template name="a"/>
            </xsl:when>
            <xsl:otherwise>
              <xsl:call-template name="title"/>
            </xsl:otherwise>
          </xsl:choose>
        </div>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template match="*" mode="toclocal"/>
  <xsl:template match="tei:div" mode="toclocal">
    <xsl:param name="localid"/>
    <xsl:variable name="children" select="tei:castList | tei:div | tei:titlePage"/>
    <li>
      <xsl:attribute name="class">
        <xsl:choose>
          <xsl:when test="generate-id() = $localid">here</xsl:when>
          <xsl:when test="ancestor::*[generate-id() = $localid]">descendant</xsl:when>
          <xsl:when test="descendant::*[generate-id() = $localid]">ancestor</xsl:when>
          <xsl:otherwise>collateral</xsl:otherwise>
        </xsl:choose>
      </xsl:attribute>
      <!-- link only on last split child -->
      <xsl:choose>
        <!-- no link when no split -->
        <xsl:when test="descendant::*[key('split', generate-id())]">
          <div>
            <xsl:call-template name="title"/>
          </div>
        </xsl:when>
        <xsl:when test="key('split', generate-id())">
          <a>
            <xsl:attribute name="href">
              <xsl:choose>
                <xsl:when test="generate-id() = $localid">#</xsl:when>
                <xsl:otherwise>
                  <xsl:call-template name="href"/>
                </xsl:otherwise>
              </xsl:choose>
            </xsl:attribute>
            <xsl:call-template name="title"/>
          </a>
        </xsl:when>
        <xsl:otherwise>
          <xsl:call-template name="a"/>
        </xsl:otherwise>
      </xsl:choose>
      <xsl:choose>
        <!-- No descendant -->
        <xsl:when test="not($children)"/>
        <!-- A descendant with a file generated -->
        <xsl:when test="descendant::*[key('split', generate-id())]">
          <ul>
            <xsl:apply-templates select="$children" mode="toclocal">
              <xsl:with-param name="localid" select="$localid"/>
            </xsl:apply-templates>
          </ul>
        </xsl:when>
        <!-- local tree, go in, forget localid -->
        <xsl:when test="generate-id() = $localid">
          <ul>
            <xsl:apply-templates select="$children" mode="toclocal"/>
          </ul>
        </xsl:when>
        <!-- in local tree -->
        <xsl:when test="not($localid)">
          <ul>
            <xsl:apply-templates select="$children" mode="toclocal"/>
          </ul>
        </xsl:when>
        <!-- Should ne  -->
      </xsl:choose>
    </li>
  </xsl:template>
  

  <xsl:template name="toc-header">
    <header>
      <a>
        <xsl:attribute name="href">
          <xsl:for-each select="/*/tei:text">
            <xsl:call-template name="href"/>
          </xsl:for-each>
        </xsl:attribute>
        <xsl:if test="$byline">
          <xsl:copy-of select="$byline"/>
          <xsl:text> </xsl:text>
        </xsl:if>
        <xsl:if test="$docdate != ''">
          <span class="docDate">
            <xsl:text> (</xsl:text>
            <xsl:value-of select="$docdate"/>
            <xsl:text>)</xsl:text>
          </span>
        </xsl:if>
        <br/>
        <xsl:copy-of select="$doctitle"/>
      </a>
    </header>
  </xsl:template>

  <!-- Produce a link for different kind of target elements -->
  <xsl:template name="a">
    <xsl:param name="title"/>
    <xsl:choose>
      <xsl:when test="$title ">
        <xsl:apply-templates select="." mode="a">
          <xsl:with-param name="title" select="$title"/>
        </xsl:apply-templates>
      </xsl:when>
      <xsl:otherwise>
        <xsl:apply-templates select="." mode="a"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Default template  -->
  <xsl:template match="node()" mode="a">
    <b style="color:red;">&lt;<xsl:value-of select="name()"/> mode="a"&gt;</b>
  </xsl:template>
  <xsl:template match="tei:pb" mode="a">
    <a>
      <xsl:attribute name="href">
        <xsl:call-template name="href"/>
      </xsl:attribute>
      <xsl:variable name="n" select="normalize-space(translate(@n, '()[]{}', ''))"/>
      <xsl:choose>
        <xsl:when test="$n != ''  and contains('0123456789IVXDCM', substring(@n,1,1))">p. <xsl:value-of select="$n"/></xsl:when>
        <xsl:otherwise>
          <xsl:value-of select="$n"/>
        </xsl:otherwise>
      </xsl:choose>
    </a>
  </xsl:template>
  <!--
  <xsl:template match="tei:castList" mode="a">
    <xsl:choose>
      <xsl:when test="tei:head">
        <xsl:apply-templates select="tei:head"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="message"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  -->
  <!-- section, liées sur leur titres  -->
  <xsl:template match="tei:body | tei:back | tei:castList | tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7 | tei:front | tei:group | tei:text" mode="a">
    <xsl:param name="class"/>
    <!-- titre long -->
    <xsl:param name="title">
      <xsl:call-template name="title"/>
    </xsl:param>
    <a>
      <xsl:attribute name="href">
        <xsl:call-template name="href"/>
      </xsl:attribute>
      <xsl:if test="$class">
        <xsl:attribute name="class">
          <xsl:value-of select="$class"/>
        </xsl:attribute>
      </xsl:if>
      <!-- Spec titre court ? <index> ? -->
      <xsl:copy-of select="$title"/>
      <!-- compte d'items -->
      <xsl:if test="self::tei:group">
        <xsl:text> </xsl:text>
        <small>
          <xsl:text>(</xsl:text>
          <xsl:value-of select="count(.//tei:text)"/>
          <xsl:text>)</xsl:text>
        </small>
      </xsl:if>
    </a>
  </xsl:template>
  <!-- affichage de noms avec initiales (par ex resp) -->
  <xsl:template match="tei:principal" mode="a">
    <a class="resp">
      <xsl:attribute name="href">
        <xsl:call-template name="href"/>
      </xsl:attribute>
      <xsl:attribute name="title">
        <xsl:value-of select="normalize-space(.)"/>
      </xsl:attribute>
      <xsl:value-of select="@xml:id | @id"/>
    </a>
  </xsl:template>
  <!-- Liens courts vers un nom -->
  <xsl:template match="tei:name[@xml:id]" mode="a">
    <a href="#{@xml:id}">
      <xsl:variable name="text">
        <xsl:apply-templates select="text()"/>
      </xsl:variable>
      <xsl:choose>
        <xsl:when test="tei:addName|tei:forename|tei:surname">
          <xsl:apply-templates select="tei:addName|tei:forename|tei:surname"/>
        </xsl:when>
        <xsl:when test="normalize-space($text) != ''">
          <xsl:value-of select="normalize-space($text)"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:apply-templates/>
        </xsl:otherwise>
      </xsl:choose>
    </a>
  </xsl:template>


  <!--
<h3>mode="li" toc</h3>
-->
  <!-- default, stop -->
  <xsl:template match="node()" mode="li"/>
  <xsl:template match="tei:castList" mode="li">
    <li>
      <xsl:call-template name="a"/>
    </li>
  </xsl:template>

  <xsl:template match="tei:back | tei:body | tei:front" mode="li">
    <xsl:param name="class">tree</xsl:param>
    <!-- un truc pour pouvoir maintenir ouvert des niveaux de table des matières -->
    <xsl:param name="less" select="0"/>
    <!-- limit depth -->
    <xsl:param name="depth"/>
    <xsl:variable name="children" select="tei:group | tei:text | tei:div 
      | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7 "/>
    <xsl:choose>
      <xsl:when test="count($children) &lt; 1"/>
      <xsl:when test="count($children) = 1">
        <li>
          <xsl:variable name="title">
            <xsl:call-template name="title"/>
          </xsl:variable>
          <xsl:for-each select="$children">
            <xsl:choose>
              <xsl:when test="tei:head | @type = 'dedication' ">
                <xsl:call-template name="a"/>
              </xsl:when>
              <xsl:otherwise>
                <xsl:call-template name="a">
                  <xsl:with-param name="title" select="$title"/>
                </xsl:call-template>
              </xsl:otherwise>
            </xsl:choose>
          </xsl:for-each>
        </li>
      </xsl:when>
      <!-- simple content ? -->
      <xsl:when test="not(tei:castList | tei:div | tei:div1 | tei:titlePage)">
        <li>
          <xsl:call-template name="a"/>
        </li>
      </xsl:when>
      <xsl:when test="self::tei:front or self::tei:back">
        <li class="more {local-name()}">
          <span>
            <xsl:call-template name="title"/>
          </span>
          <ul>
            <xsl:for-each select="tei:castList | tei:div | tei:div1 | tei:titlePage">
              <xsl:choose>
                <!-- ??? first section with no title, no forged title -->
                <xsl:when test="self::div and position() = 1 and not(tei:head) and ../tei:head "/>
                <xsl:otherwise>
                  <xsl:apply-templates select="." mode="li"/>
                </xsl:otherwise>
              </xsl:choose>
            </xsl:for-each>
          </ul>
        </li>
      </xsl:when>
      <xsl:when test="self::tei:body">
        <xsl:apply-templates select="tei:castList | tei:div | tei:div1 | tei:titlePage" mode="li"/>
      </xsl:when>
      <!-- div content -->
      <xsl:otherwise>
        <xsl:apply-templates select="tei:castList | tei:div | tei:div1 | tei:titlePage" mode="li"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:group/tei:text" mode="li">
    <li class="more">
      <xsl:call-template name="a"/>
      <xsl:choose>
        <!-- simple content -->
        <xsl:when test="not(tei:front|tei:back) and tei:body/tei:p | tei:body/tei:l | tei:body/tei:list | tei:body/tei:argument | tei:body/tei:table | tei:body/tei:docTitle | tei:body/tei:docAuthor"/>
        <xsl:otherwise>
          <ul>
            <xsl:apply-templates select="tei:front" mode="li"/>
            <xsl:apply-templates select="tei:body" mode="li"/>
            <xsl:apply-templates select="tei:back" mode="li"/>
          </ul>
        </xsl:otherwise>
      </xsl:choose>
    </li>
  </xsl:template>
  <!-- sectionnement, traverser -->
  <xsl:template match=" tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7 | tei:group " mode="li">
    <xsl:param name="class">tree</xsl:param>
    <!-- un truc pour pouvoir maintenir ouvert des niveaux de table des matières -->
    <xsl:param name="less" select="0"/>
    <!-- limit depth -->
    <xsl:param name="depth"/>
    <!-- enfants ? -->
    <xsl:variable name="children" select="tei:group | tei:text | tei:div[tei:head] 
      | tei:div0[tei:head] | tei:div1[tei:head] | tei:div2[tei:head] | tei:div3[tei:head] | tei:div4[tei:head] | tei:div5[tei:head] | tei:div6[tei:head] | tei:div7[tei:head] "/>
    <li>
      <xsl:choose>
        <!-- last level -->
        <xsl:when test="count($children) &lt; 1"/>
        <!-- let open -->
        <xsl:when test="number($depth) &lt; 2"/>
        <xsl:when test="number($less) &gt; 0">
          <xsl:attribute name="class">less</xsl:attribute>
        </xsl:when>
        <xsl:otherwise>
          <xsl:attribute name="class">more</xsl:attribute>
        </xsl:otherwise>
      </xsl:choose>
      <xsl:choose>
        <xsl:when test="count($children) &gt; 0">
          <xsl:call-template name="a"/>
          <ul>
            <xsl:if test="$class">
              <xsl:attribute name="class">
                <xsl:value-of select="$class"/>
              </xsl:attribute>
            </xsl:if>
            <xsl:for-each select="tei:back | tei:body | tei:castList | tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7 | tei:front | tei:group | tei:text">
              <xsl:choose>
                <!-- ??? first section with no title, no forged title -->
                <xsl:when test="not(tei:head)"/>
                <xsl:otherwise>
                  <xsl:apply-templates select="." mode="li">
                    <xsl:with-param name="less" select="number($less) - 1"/>
                    <xsl:with-param name="depth" select="number($depth) - 1"/>
                    <xsl:with-param name="class"/>
                  </xsl:apply-templates>
                </xsl:otherwise>
              </xsl:choose>
            </xsl:for-each>
          </ul>
        </xsl:when>
        <xsl:otherwise>
          <xsl:call-template name="a"/>
        </xsl:otherwise>
      </xsl:choose>
    </li>
    <!-- ??
          <xsl:when test="(/*/tei:text/tei:front and count(.|/*/tei:text/tei:front) = 1) 
            or (/*/tei:text/tei:back and count(.|/*/tei:text/tei:back) = 1)">
            <xsl:call-template name="a"/>
          </xsl:when>
      -->
  </xsl:template>
  <xsl:template name="toc-front">
    <xsl:for-each select="/*/tei:text/tei:front">
      <xsl:call-template name="paratoc"/>
    </xsl:for-each>
  </xsl:template>
  <xsl:template name="toc-back">
    <xsl:for-each select="/*/tei:text/tei:back">
      <xsl:call-template name="paratoc"/>
    </xsl:for-each>
  </xsl:template>
  <xsl:template name="paratoc">
    <xsl:choose>
      <xsl:when test="tei:p | tei:l | tei:list | tei:argument | tei:table | tei:docTitle | tei:docAuthor">
        <xsl:call-template name="a"/>
      </xsl:when>
      <xsl:when test="tei:div[normalize-space(.) != '']|tei:div1[normalize-space(.) != '']">
        <ul class="tree">
          <xsl:apply-templates select="*[self::tei:div|self::tei:div1][normalize-space(.) != '']" mode="li"/>
        </ul>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  <!--
<h3>mode="bibl" (ligne bibliographique)</h3>

<p>
Dégager une ligne biliographique d'un élément, avec des enregistrements bibliographique structurés.
</p>
-->
  <xsl:template match="tei:fileDesc " mode="bibl">
    <!-- titre, requis -->
    <xsl:apply-templates select="tei:titleStmt/tei:title" mode="bibl"/>
    <xsl:if test="tei:titleStmt/tei:principal">
      <!-- direction, requis -->
      <xsl:text>, dir. </xsl:text>
      <xsl:for-each select="tei:titleStmt/tei:principal">
        <xsl:value-of select="."/>
        <xsl:choose>
          <xsl:when test="position() = last()">, </xsl:when>
          <xsl:otherwise>, </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </xsl:if>
    <!-- édition optionnel -->
    <xsl:if test="tei:editionStmt/@n">
      <xsl:value-of select="tei:editionStmt/@n"/>
      <sup>e</sup>
      <xsl:text> éd., </xsl:text>
    </xsl:if>
    <xsl:variable name="date">
      <!-- date, requis -->
      <xsl:value-of select="tei:publicationStmt/tei:date"/>
      <!-- Collection, optionnel -->
      <xsl:apply-templates select="tei:seriesStmt" mode="bibl"/>
    </xsl:variable>
    <xsl:if test="$date != '' and tei:publicationStmt/tei:idno">
      <xsl:value-of select="$date"/>
      <xsl:text>, </xsl:text>
    </xsl:if>
    <!-- URI de référence, requis -->
    <xsl:apply-templates select="tei:publicationStmt/tei:idno"/>
    <xsl:text>.</xsl:text>
  </xsl:template>
  <!-- Information de série dans une ligne bibliographique -->
  <xsl:template match="tei:seriesStmt" mode="bibl">
    <span class="seriesStmt">
      <xsl:text> (</xsl:text>
      <xsl:for-each select="*[not(@type='URI')]">
        <xsl:apply-templates select="."/>
        <xsl:choose>
          <xsl:when test="position() = last()"/>
          <xsl:otherwise>, </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
      <xsl:text>)</xsl:text>
    </span>
  </xsl:template>
  <!-- titre -->
  <xsl:template match="tei:title" mode="bibl">
    <xsl:text> </xsl:text>
    <em class="title">
      <xsl:apply-templates/>
    </em>
  </xsl:template>

</xsl:transform>
