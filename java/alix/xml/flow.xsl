<?xml version="1.0" encoding="UTF-8"?>
<!--

TEI to HTML, main flow of text

LGPL  http://www.gnu.org/licenses/lgpl.html
© 2019 Frederic.Glorieux@fictif.org et OPTEOS
© 2013 Frederic.Glorieux@fictif.org et LABEX OBVIL
© 2012 Frederic.Glorieux@fictif.org
© 2010 Frederic.Glorieux@fictif.org et École nationale des chartes
© 2007 Frederic.Glorieux@fictif.org
© 2005 ajlsm.com (Cybertheses)

XSLT 1.0 is compatible browser, PHP, Python, Java…
-->
<xsl:transform version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns="http://www.w3.org/1999/xhtml"
  xmlns:eg="http://www.tei-c.org/ns/Examples"
  xmlns:tei="http://www.tei-c.org/ns/1.0"
  xmlns:epub="http://www.idpf.org/2007/ops"
  exclude-result-prefixes="eg tei epub"
  >
  <!-- Import shared templates -->
  <xsl:import href="common.xsl"/>
  <xsl:output encoding="UTF-8" indent="yes" method="xml" omit-xml-declaration="yes"/>
  <!-- What kind of root element to output ? html, div, article -->
  <xsl:param name="root" select="$html"/>
  <xsl:key name="split" match="/" use="'root'"/>
  <!-- test if there are code examples to js prettyprint -->
  <xsl:key name="prettify" match="eg:egXML|tei:tag" use="1"/>
  <!-- mainly in verse -->
  <xsl:variable name="verse" select="count(/*/tei:text/tei:body//tei:l) &gt; count(/*/tei:text/tei:body//tei:p)"/>

  <!--
Sections
  -->
  <xsl:template match="tei:elementSpec">
    <article>
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
      <xsl:call-template name="atts"/>
      <h1>
        <xsl:text>&lt;</xsl:text>
        <xsl:value-of select="@ident"/>
        <xsl:text>&gt;</xsl:text>
      </h1>
      <xsl:apply-templates select="*"/>
    </article>
  </xsl:template>

  <xsl:template match="tei:front">
    <xsl:param name="level" select="count(ancestor::tei:group)"/>
    <xsl:variable name="element">
      <xsl:choose>
        <xsl:when test="$format = $epub2">div</xsl:when>
        <xsl:otherwise>section</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$element}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:if test="$format = $epub3">
        <xsl:attribute name="epub:type">frontmatter</xsl:attribute>
      </xsl:if>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates select="*">
        <xsl:with-param name="level" select="$level"/>
      </xsl:apply-templates>
    </xsl:element>
  </xsl:template>
  <xsl:template match="tei:back">
    <xsl:param name="notes" select="not(ancestor::tei:group)"/>
    <xsl:param name="level" select="count(ancestor::tei:group)"/>
    <xsl:choose>
      <xsl:when test="normalize-space(.) = ''"/>
      <xsl:otherwise>
        <xsl:variable name="element">
          <xsl:choose>
            <xsl:when test="$format = $epub2">div</xsl:when>
            <xsl:otherwise>section</xsl:otherwise>
          </xsl:choose>
        </xsl:variable>
        <xsl:element name="{$element}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:if test="$format = $epub3">
            <xsl:attribute name="epub:type">backmatter</xsl:attribute>
          </xsl:if>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates select="*">
            <xsl:with-param name="level" select="$level "/>
          </xsl:apply-templates>
        </xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:body">
    <xsl:param name="level" select="count(ancestor::tei:group)"/>
    <xsl:variable name="element">
      <xsl:choose>
        <xsl:when test="$format = $epub2">div</xsl:when>
        <xsl:otherwise>section</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$element}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:if test="$format = $epub3">
        <xsl:attribute name="epub:type">bodymatter</xsl:attribute>
      </xsl:if>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates>
        <xsl:with-param name="level" select="$level "/>
      </xsl:apply-templates>
    </xsl:element>
  </xsl:template>
  <!--
    TODO ? notes dans les groupes ?
  <xsl:template match="tei:text/tei:group">
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="$format = 'html5'">section</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:call-template name="atts"/>
      <xsl:comment>
        <xsl:call-template name="path"/>
      </xsl:comment>
      <xsl:apply-templates/>
      <xsl:call-template name="notes">
        <xsl:with-param name="texte" select=".//*[not()]"/>
      </xsl:call-template>
    </xsl:element>
  </xsl:template>
  -->
  <!-- Sections -->
  <xsl:template match="tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7 | tei:epilogue | tei:preface | tei:group | tei:prologue">
    <xsl:param name="level" select="count(ancestor::*) - 2"/>
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="self::tei:group">div</xsl:when>
        <xsl:when test="$format = $epub2">div</xsl:when>
        <xsl:otherwise>section</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
      <xsl:call-template name="atts">
        <xsl:with-param name="class">level<xsl:value-of select="$level + 1"/></xsl:with-param>
      </xsl:call-template>
      <!-- attributs epub3 -->
      <xsl:choose>
        <xsl:when test="$format != $epub3"/>
        <xsl:when test="@type = 'bibliography'">
          <xsl:attribute name="epub:type">bibliography</xsl:attribute>
        </xsl:when>
        <xsl:when test="@type = 'glossary'">
          <xsl:attribute name="epub:type">glossary</xsl:attribute>
        </xsl:when>
        <xsl:when test="@type = 'index'">
          <xsl:attribute name="epub:type">index</xsl:attribute>
        </xsl:when>
        <xsl:when test="@type = 'chapter' or @type = 'act' or @type='article'">
          <xsl:attribute name="epub:type">chapter</xsl:attribute>
        </xsl:when>
        <xsl:when test="ancestor::tei:div[@type = 'chapter' or @type = 'act' or @type='article']">
          <xsl:attribute name="epub:type">subchapter</xsl:attribute>
        </xsl:when>
        <xsl:when test=".//tei:div[@type = 'chapter' or @type = 'act' or @type='article']">
          <xsl:attribute name="epub:type">part</xsl:attribute>
        </xsl:when>
        <!-- dangerous ? -->
        <xsl:when test="self::*[key('split', generate-id())]">
          <xsl:attribute name="epub:type">chapter</xsl:attribute>
        </xsl:when>
      </xsl:choose>
      <!-- First element is an empty(?) page break, may come from numerisation or text-processor -->
      <xsl:variable name="name" select="local-name()"/>
      <xsl:choose>
        <xsl:when test="$format != $epub2 and $format != $epub3"/>
        <!-- first section with close to the parent title, no page break -->
        <xsl:when test="not(preceding-sibling::*[$name = local-name()]) and not(preceding-sibling::tei:p|preceding-sibling::tei:quote)"/>
        <!-- start of a file  -->
        <xsl:when test="$level &lt; 2"/>
        <!-- deep level, do nothing -->
        <xsl:when test="$level &gt; 3"/>
        <!-- hard page break ? -->
        <xsl:otherwise/>
      </xsl:choose>
      <xsl:apply-templates>
        <xsl:with-param name="level" select="$level + 1"/>
      </xsl:apply-templates>
    </xsl:element>
  </xsl:template>
  <!-- Floating division -->
  <xsl:template match="tei:floatingText">
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="$format = 'html5'">aside</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </xsl:element>
  </xsl:template>
  <!-- Cartouche d'entête d'acte -->
  <xsl:template match="tei:group/tei:text/tei:front ">
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="$format = 'html5'">header</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:call-template name="atts"/>
      <!--
          <xsl:choose>
            <xsl:when test="../@n">
              <xsl:value-of select="../@n"/>
            </xsl:when>
            <xsl:when test="tei:titlePart[starts-with(@type, 'num')]">
              <xsl:value-of select="tei:titlePart[starts-with(@type, 'num')]"/>
            </xsl:when>
          </xsl:choose>
        -->
      <xsl:apply-templates/>
      <!-- VJ : inscire la mention "D'après témoin" -->
      <xsl:apply-templates select=".//tei:witness[@ana='edited']" mode="according"/>
    </xsl:element>
  </xsl:template>
  <!-- Éléments coupés de la sortie, ou à implémenter -->
  <xsl:template match="tei:divGen "/>
  <!--
<h3>Titres</h3>

  -->
  <!-- Titre en entête -->
  <xsl:template match="tei:titleStmt/tei:title">
    <h1>
      <xsl:choose>
        <xsl:when test="@type">
          <xsl:attribute name="class">
            <xsl:value-of select="@type"/>
          </xsl:attribute>
        </xsl:when>
        <xsl:when test="../tei:title[@type='main']">
          <xsl:attribute name="class">notmain</xsl:attribute>
        </xsl:when>
      </xsl:choose>
      <xsl:apply-templates/>
    </h1>
  </xsl:template>
  <!-- Page de titre -->
  <xsl:template match="tei:titlePage">
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="$format = 'html5'">section</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:if test="$format = $epub3">
        <xsl:attribute name="epub:type">titlepage</xsl:attribute>
      </xsl:if>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="tei:titlePage/tei:performance"/>
  <!-- <h[1-6]> titres avec niveaux hiérarchiques génériques selon le nombre d'ancêtres, il est possible de paramétrer le niveau, pour commencer à 1 en haut de document généré -->
  <xsl:template match="tei:head">
    <xsl:param name="level" select="count(ancestor::tei:*) - 2"/>
    <xsl:variable name="name">
      <xsl:choose>
        <xsl:when test="normalize-space(.) = ''"/>
        <xsl:when test="parent::tei:front | parent::tei:text | parent::tei:back ">h1</xsl:when>
        <xsl:when test="$level &lt; 1">h1</xsl:when>
        <xsl:when test="$level &gt; 7">h6</xsl:when>
        <xsl:otherwise>h<xsl:value-of select="$level"/></xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:if test="$name != ''">
      <xsl:apply-templates select="tei:pb"/>
      <xsl:element name="{$name}" namespace="http://www.w3.org/1999/xhtml">
        <xsl:call-template name="atts">
          <xsl:with-param name="class">
            <xsl:value-of select="../@type"/>
            <xsl:if test="$verse"> verse</xsl:if>
          </xsl:with-param>
        </xsl:call-template>
        <xsl:apply-templates select="node()[local-name()!='pb']"/>
        <xsl:if test="not(following-sibling::*[1][self::tei:head]) and $format != $epub2 and $format != $epub3">
          <a class="bookmark">
            <xsl:attribute name="href">
              <xsl:for-each select="ancestor::tei:div[1]">
                <xsl:call-template name="href"/>
              </xsl:for-each>
            </xsl:attribute>
            <xsl:text> §</xsl:text>
          </a>
        </xsl:if>
      </xsl:element>
    </xsl:if>
  </xsl:template>
  <!-- Autres titres -->
  <xsl:template match="tei:titlePart">
    <div>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </div>
  </xsl:template>
  <!--
<h3>Paragraphs</h3>
  -->
  <!--  -->
  <!-- Quotation (may contain paragraphs) -->
  <xsl:template match="tei:epigraph | tei:exemplum | tei:remarks">
    <blockquote>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </blockquote>
  </xsl:template>
  <!-- labelled paragraphe container -->
  <xsl:template match="tei:classes | tei:content">
    <fieldset>
      <xsl:call-template name="atts"/>
      <legend>
        <xsl:call-template name="message">
          <xsl:with-param name="id" select="local-name()"/>
        </xsl:call-template>
      </legend>
      <xsl:apply-templates/>
    </fieldset>
  </xsl:template>
  <!-- Contains blocks, but are no sections -->
  <xsl:template match="tei:argument | tei:closer | tei:def | tei:docTitle | tei:entry | tei:form | tei:postscript  | tei:entry/tei:xr | tei:opener">
    <xsl:if test=". != ''">
      <div>
        <xsl:call-template name="atts"/>
        <xsl:apply-templates/>
      </div>
    </xsl:if>
  </xsl:template>
  <!-- To think, rendering ? -->
  <xsl:template match="tei:sp">
    <div>
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </div>
  </xsl:template>
  <!-- Paragraph blocs (paragraphs are not allowed in it) -->
  <xsl:template match="tei:p">
    <xsl:variable name="el">
      <xsl:choose>
        <!-- If a margin note contains a block level element, browser will complain with p//p -->
        <xsl:when test=".//tei:note[@place='margin']">div</xsl:when>
        <xsl:otherwise>p</xsl:otherwise>
      </xsl:choose>  
    </xsl:variable>
    <xsl:element name="{$el}">
      <xsl:variable name="prev" select="preceding-sibling::*[not(self::tei:pb)][1]"/>
      <xsl:variable name="char1" select="substring( normalize-space(.), 1, 1)"/>
      <xsl:variable name="class">
        <xsl:choose>
          <xsl:when test="contains( '-–—0123456789', $char1 )"/>
          <xsl:when test="descendant::tei:graphic">noindent</xsl:when>
          <xsl:when test="contains(concat(' ', @rend, ' '), ' indent ')"/>
          <!--
          <xsl:when test="$prev and contains('-–—', substring(normalize-space($prev), 1, 1))"/>
          -->
          <xsl:when test="local-name($prev) ='p' and translate($prev, '*∾  ','')!=''"/>
          <xsl:otherwise>autofirst</xsl:otherwise>
        </xsl:choose>
      </xsl:variable>     
      <xsl:call-template name="atts">
        <xsl:with-param name="class" select="$class"/>
      </xsl:call-template>
      <xsl:if test="@n">
        <small class="n">
          <xsl:text>[</xsl:text>
          <xsl:value-of select="@n"/>
          <xsl:text>]</xsl:text>
        </small>
        <xsl:text> </xsl:text>
      </xsl:if>
      <xsl:apply-templates/>
      <xsl:if test=".=''"> </xsl:if>
    </xsl:element>
  </xsl:template>
  <xsl:template match="tei:acheveImprime | tei:approbation | tei:byline | tei:caption | tei:dateline | tei:desc | tei:docEdition | tei:docImprint | tei:imprimatur | tei:performance | tei:premiere | tei:printer | tei:privilege | tei:signed | tei:salute | tei:set | tei:trailer
    ">
    <xsl:variable name="el">
      <xsl:choose>
        <xsl:when test="self::tei:trailer">p</xsl:when>
        <xsl:when test="self::tei:docImprint">div</xsl:when>
        <xsl:when test="parent::tei:titlePage">p</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:if test="normalize-space(.) != '' ">
      <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
        <xsl:call-template name="atts"/>
        <xsl:apply-templates/>
      </xsl:element>
    </xsl:if>
  </xsl:template>
  <xsl:template match="tei:address">
    <address>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </address>
  </xsl:template>
  <!-- Ne pas sortir les saut de ligne dans du texte préformaté -->
  <xsl:template match="tei:eg/tei:lb"/>
  <!-- Couillards et autres culs de lampe -->
  <xsl:template match="tei:ab">
    <xsl:choose>
      <xsl:when test="@type='hr'">
        <hr align="center" width="30%"/>
      </xsl:when>
      <xsl:when test="normalize-space(.) = ''">
        <div>
          <xsl:call-template name="atts"/>
          <xsl:text> </xsl:text>
          <xsl:apply-templates/>
        </div>
      </xsl:when>
      <xsl:otherwise>
        <div>
          <xsl:call-template name="atts">
            <xsl:with-param name="class">
              <xsl:if test="substring(normalize-space(.), 1, 1) = '*'">star</xsl:if>
            </xsl:with-param>
          </xsl:call-template>
          <xsl:apply-templates/>
        </div>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Preformated text -->
  <xsl:template match="tei:eg">
    <pre>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </pre>
  </xsl:template>
  <!--
<h3>Lists</h3>
-->
  <!-- Différentes listes, avec prise en charge xhtml correcte des titres (inclus dans un blockquote)  -->
  <xsl:template match="tei:list | tei:listWit | tei:recordHist">
    <xsl:variable name="el">
      <xsl:choose>
        <xsl:when test="not(@rend)">ul</xsl:when>
        <xsl:when test="contains(' ordered ol Décimale ', concat(' ', @type, ' ')) ">ol</xsl:when>
        <xsl:when test="contains(' a) a. decimal Décimal decimal-leading-zero 1. 1° 1) I I. lower-alpha lower-latin ol upper-alpha upper-latin upper-roman  ', concat(' ', @rend, ' ')) ">ol</xsl:when>
        <xsl:otherwise>ul</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <!-- test if items as already items -->
    <xsl:variable name="first" select="substring(normalize-space(tei:item), 1, 1)"/>
    <xsl:variable name="none" select="contains('-–—•1', $first)"/>
    <xsl:choose>
      <!-- liste titrée à mettre dans un conteneur-->
      <xsl:when test="tei:head">
        <div class="{local-name()}">
          <xsl:apply-templates select="tei:head"/>
          <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
            <xsl:call-template name="atts">
              <xsl:with-param name="class">
                <xsl:if test="$none">none</xsl:if>
              </xsl:with-param>
            </xsl:call-template>
            <xsl:apply-templates select="*[local-name() != 'head']"/>
          </xsl:element>
        </div>
      </xsl:when>
      <xsl:otherwise>
        <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="atts">
            <xsl:with-param name="class">
              <xsl:if test="$none">none</xsl:if>
            </xsl:with-param>
          </xsl:call-template>
          <xsl:apply-templates/>
        </xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Specif case, a bibliographic reference, followed by a detailed list of copies, no need to include in a <ul> -->
  <xsl:template match="tei:listBibl[count(*)=2][name(*[1])='bibl'][name(*[2])='listBibl']">
    <xsl:apply-templates/>
  </xsl:template>
  
  <xsl:template match="tei:listBibl">
    <xsl:choose>
      <xsl:when test="tei:head">
        <div class="{local-name()}">
          <xsl:apply-templates select="tei:head"/>
          <ul>
            <xsl:call-template name="atts"/>
            <xsl:for-each select="*[not(self::tei:head)]">
              <li>
                <xsl:apply-templates select="."/>
              </li>
            </xsl:for-each>
          </ul>
        </div>
      </xsl:when>
      <xsl:otherwise>
        <ul>
          <xsl:call-template name="atts"/>
          <xsl:for-each select="*">
            <li>
              <xsl:call-template name="atts"/>
              <xsl:apply-templates select="."/>
            </li>
          </xsl:for-each>
        </ul>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Pour l’instant ne pas afficher les configurations -->
  <xsl:template match="tei:listPerson">
    <span>
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
    </span>
  </xsl:template>
  <!-- Pseudo-listes  -->
  <xsl:template match="tei:respStmt">
    <xsl:variable name="name" select="name()"/>
    <!-- Plus de 2 item du même nom, voir s'il y a un titre dans le fichier de messages -->
    <xsl:if test="../*[name() = $name][2] and count(../*[name() = $name][1]|.) = 1">
      <xsl:variable name="message">
        <xsl:call-template name="message"/>
      </xsl:variable>
      <xsl:if test="string($message) != ''">
        <p class="{local-name()}">
          <xsl:value-of select="$message"/>
        </p>
      </xsl:if>
    </xsl:if>
    <div>
      <xsl:call-template name="atts"/>
      <xsl:if test="*/@from|*/@to">
        <xsl:text>(</xsl:text>
        <xsl:value-of select="*/@from"/>
        <xsl:text> — </xsl:text>
        <xsl:value-of select="*/@to"/>
        <xsl:text>) </xsl:text>
      </xsl:if>
      <xsl:for-each select="*">
        <xsl:apply-templates select="."/>
        <xsl:choose>
          <xsl:when test="position() = last()">
            <xsl:call-template name="dot"/>
          </xsl:when>
          <xsl:when test="position()=1 and following-sibling::*"> : </xsl:when>
          <xsl:otherwise> ; </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </div>
  </xsl:template>
  <xsl:template match="tei:resp">
    <xsl:apply-templates/>
  </xsl:template>
  <!-- Liste de type index -->
  <xsl:template match="tei:list[@type='index']">
    <div>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates select="tei:head"/>
      <!-- s'il n'y a pas de division spéciale, barre de lettres -->
      <!--
      <xsl:choose>
        <xsl:when test="not(tei:list)">
          <div class="alpha">
            <xsl:call-template name="alpha_href"/>
          </div>
        </xsl:when>
      </xsl:choose>
      -->
      <ul class="index">
        <xsl:apply-templates select="*[local-name() != 'head']"/>
      </ul>
    </div>
  </xsl:template>
  <!-- Barre de lettres, supposée appelée dans le contexte d'un index à plat -->
  <xsl:template name="alpha_href">
    <!-- Préfixe de l'identifiant -->
    <xsl:param name="prefix" select="concat(@xml:id, '.')"/>
    <xsl:param name="alpha">abcdefghijklmnopqrstuvwxyz</xsl:param>
    <xsl:choose>
      <!-- ne pas oublier de stopper à temps -->
      <xsl:when test="$alpha =''"/>
      <xsl:otherwise>
        <xsl:variable name="lettre" select="substring($alpha, 1, 1)"/>
        <!-- tester si la lettre existe avant de l'écrire -->
        <xsl:if test="*[translate(substring(., 1, 1), $idfrom, $idto) = $lettre]">
          <xsl:if test="$lettre != 'a'"> </xsl:if>
          <!-- pas d'espace insécable -->
          <a href="#{$prefix}{$lettre}" target="_self">
            <xsl:value-of select="translate ( $lettre, $lc, $uc)"/>
          </a>
        </xsl:if>
        <xsl:call-template name="alpha_href">
          <xsl:with-param name="alpha" select="substring($alpha, 2)"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Titre d'une liste -->
  <xsl:template match="tei:list/tei:head">
    <p class="list">
      <xsl:apply-templates/>
    </p>
  </xsl:template>
  <!-- <item>, item de liste -->
  <xsl:template match="tei:item">
    <li>
      <xsl:call-template name="atts"/>
      <!-- unplug, maybe expensive -->
      <!--
      <xsl:if test="../@type='index' and not(../tei:list)">
        <xsl:variable name="lettre" select="translate(substring(., 1, 1), $iso, $min)"/>
        <xsl:if test="translate(substring(preceding-sibling::tei:item[1], 1, 1), $iso, $min) != $lettre">
          <a id="{../@xml:id}.{$lettre}">&#x200c;</a>
        </xsl:if>
      </xsl:if>
      -->
      <xsl:apply-templates/>
    </li>
  </xsl:template>
  <!-- term list -->
  <xsl:template match="tei:list[@type='gloss' or tei:label]">
    <xsl:choose>
      <!-- liste titrée à mettre dans un conteneur-->
      <xsl:when test="tei:head">
        <div class="{local-name()}">
          <xsl:apply-templates select="tei:head"/>
          <dl class="dl">
            <xsl:call-template name="atts"/>
            <xsl:apply-templates select="*[local-name() != 'head']"/>
          </dl>
        </div>
      </xsl:when>
      <xsl:otherwise>
        <dl>
          <xsl:call-template name="atts">
            <xsl:with-param name="class">dl</xsl:with-param>
          </xsl:call-template>
          <xsl:apply-templates/>
        </dl>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:list[@type='gloss' or tei:label]/tei:label">
    <dt>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </dt>
  </xsl:template>
  <xsl:template match="tei:list[@type='gloss' or tei:label]/tei:item">
    <dd>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </dd>
  </xsl:template>
  <xsl:template match="tei:castList">
    <div>
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates select="tei:head | tei:p"/>
      <ul class="castList">
        <xsl:for-each select="*[not(self::tei:head) and not(self::tei:p)]">
          <li>
            <xsl:call-template name="atts"/>
            <xsl:apply-templates select="."/>
          </li>
        </xsl:for-each>
      </ul>
    </div>
  </xsl:template>
  <xsl:template match="tei:castItem">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="tei:castGroup">
    <xsl:apply-templates select="tei:head | tei:note | tei:roleDesc"/>
    <ul class="castGroup">
      <xsl:for-each select="*">
        <li>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates select="."/>
        </li>
      </xsl:for-each>
    </ul>
  </xsl:template>
  <xsl:template match="tei:castGroup/tei:head">
    <xsl:call-template name="span"/>
  </xsl:template>
  <xsl:template match="tei:actor | tei:role | tei:roleDesc">
    <xsl:call-template name="span"/>
  </xsl:template>
  <!-- Glossary like dictionary entry -->
  <xsl:template match="tei:entryFree">
    <div>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </div>
  </xsl:template>
  <xsl:template match="tei:xr">
    <div>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </div>
  </xsl:template>
  <!--
Tables
 - - -
  -->
  <!-- table  -->
  <xsl:template match="tei:table">
    <hr class="clear"/>
    <table>
      <xsl:call-template name="atts">
        <xsl:with-param name="class">table</xsl:with-param>
      </xsl:call-template>
      <xsl:apply-templates/>
    </table>
  </xsl:template>
  <xsl:template match="tei:table/tei:head">
    <caption>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </caption>
  </xsl:template>
  <xsl:template match="tei:table/tei:spanGrp">
    <colgroup>
      <xsl:apply-templates/>
    </colgroup>
  </xsl:template>
  <xsl:template match="tei:table/tei:spanGrp/tei:span">
    <col>
      <xsl:call-template name="atts"/>
    </col>
  </xsl:template>
  <!-- ligne -->
  <xsl:template match="tei:row">
    <tr>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </tr>
  </xsl:template>
  <!-- Cellule  -->
  <xsl:template match="tei:cell">
    <xsl:variable name="el">
      <xsl:choose>
        <xsl:when test="@role='label'">th</xsl:when>
        <xsl:when test="../@role='label'">th</xsl:when>
        <xsl:otherwise>td</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:call-template name="atts"/>
      <xsl:if test="@rows &gt; 1">
        <xsl:attribute name="rowspan">
          <xsl:value-of select="@rows"/>
        </xsl:attribute>
      </xsl:if>
      <xsl:if test="@cols &gt; 1">
        <xsl:attribute name="colspan">
          <xsl:value-of select="@cols"/>
        </xsl:attribute>
      </xsl:if>
      <xsl:apply-templates/>
    </xsl:element>
  </xsl:template>
  <!-- vers, strophe -->

  <xsl:template match="tei:lg">
    <div>
      <xsl:call-template name="atts">
        <xsl:with-param name="class">
          <xsl:if test="@part">
            <xsl:text>part-</xsl:text>
            <xsl:value-of select="translate(@part, 'fimy', 'FIMY')"/>
          </xsl:if>
        </xsl:with-param>
      </xsl:call-template>
      <xsl:apply-templates/>
    </div>
  </xsl:template>
  <xsl:template name="l-n">
    <xsl:choose>
      <xsl:when test="@n">
        <xsl:value-of select="@n"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  <!-- ligne (ex : vers) -->
  <xsl:template match="tei:l">
    <xsl:variable name="n">
      <xsl:call-template name="l-n"/>
    </xsl:variable>
    <!-- or $prev/@n = $n SLOW
    <xsl:variable name="prev" select="preceding::tei:l[1]"/>
    -->
    <xsl:choose>
      <!-- probablement vers vide pour espacement -->
      <xsl:when test="normalize-space(.) =''">
        <br/>
      </xsl:when>
      <xsl:otherwise>
        <div>
          <xsl:variable name="pos">
            <xsl:number/>
          </xsl:variable>
          <xsl:call-template name="atts">
            <xsl:with-param name="class">
              <xsl:if test="@part">
                <xsl:text>part-</xsl:text>
                <xsl:value-of select="translate(@part, 'fimy', 'FIMY')"/>
              </xsl:if>
              <xsl:if test="@met">
                <xsl:text> </xsl:text>
                <xsl:value-of select="@met"/>
              </xsl:if>
              <!-- first verse in stanza -->
              <xsl:choose>
                <!-- Not in a stanza -->
                <xsl:when test="not(parent::tei:lg)"/>
                <!-- Is it a broken verse to align ? -->
                <xsl:when test="@part and @part != 'I'">
                  <!-- search if previous verse should be aligned -->
                  <xsl:for-each select="preceding::tei:l[@part='I'][1]">
                    <xsl:variable name="first">
                      <xsl:number/>
                    </xsl:variable>
                    <xsl:choose>
                      <xsl:when test="$first != 1"/>
                      <xsl:when test="not(parent::tei:lg)"/>
                      <xsl:when test="not(parent::tei:lg/@part) or parent::tei:lg/@part = 'I'"> first</xsl:when>
                    </xsl:choose>
                  </xsl:for-each>
                </xsl:when>
                <xsl:when test="$pos != 1"/>
                <!-- first but in a stanza fragment-->
                <xsl:when test="parent::tei:lg/@part and parent::tei:lg/@part != 'I'"/>
                <!-- first verse in a stanza -->
                <xsl:when test="$pos = 1">
                  <xsl:text> first</xsl:text>
                </xsl:when>
              </xsl:choose>
            </xsl:with-param>
          </xsl:call-template>
          <xsl:choose>
            <xsl:when test="not(number($n))"/>
            <xsl:when test="$n &lt; 1"/>
            <!-- previous verse has same number, usually a rupted verse -->
            <xsl:when test="@part='M' or @part='F'"/>
            <!-- line number could be multiple in a file, do not check repeated number in broken verses  -->
            <xsl:when test="ancestor::tei:quote"/>
            <xsl:when test="($n mod 5) = 0">
              <small class="l-n">
                <xsl:value-of select="$n"/>
                <xsl:text> </xsl:text>
              </small>
            </xsl:when>
          </xsl:choose>
          <xsl:if test="@part = 'M' or @part = 'm' or @part = 'F' or @part = 'f'  or @part = 'y'  or @part = 'Y'">
            <!-- Rupted verse, get the exact spacer from previous verse -->
            <xsl:variable name="txt">
              <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
            </xsl:variable>
            <xsl:if test="normalize-space($txt) != ''">
              <span class="spacer" style="visibility: hidden;">
                <xsl:value-of select="$txt"/>
              </span>
            </xsl:if>
          </xsl:if>
          <xsl:apply-templates/>
        </div>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:l" mode="lspacer">
    <xsl:variable name="txt">
      <xsl:apply-templates mode="title"/>
    </xsl:variable>
    <xsl:choose>
      <!-- ??? -->
      <xsl:when test="not(ancestor::tei:body)"/>
      <!-- encoding error -->
      <xsl:when test="not(@part)"/>
      <!-- encoding error -->
      <xsl:when test="@part = 'F'">
        <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="@part = 'M'">
        <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="@part = 'Y'">
        <xsl:apply-templates select="preceding::tei:l[1]" mode="lspacer"/>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="@part = 'I'">
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:when>
      <!-- No part="I" ? -->
      <xsl:otherwise>
        <xsl:value-of select="$txt"/>
        <xsl:text> </xsl:text>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!--
<h2>Caractères</h2>

<p>Balises de niveau mot, à l'intérieur d'un paragraphe.</p>

<h3>HTML</h3>

  -->
  <!-- <hi>, mise en forme typo générique -->
  <xsl:template match="tei:hi">
    <xsl:variable name="rend" select="translate(@rend, $idfrom, $idto)"/>
    <xsl:choose>
      <xsl:when test=". =''"/>
      <!-- si @rend est un nom d'élément HTML -->
      <xsl:when test="contains( ' b big em i s small strike strong sub sup tt u ', concat(' ', $rend, ' '))">
        <xsl:element name="{$rend}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </xsl:element>
      </xsl:when>
      <xsl:when test="$rend = ''">
        <em>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </em>
      </xsl:when>
      <xsl:when test="starts-with($rend, 'it')">
        <i>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </i>
      </xsl:when>
      <xsl:when test="starts-with($rend, 'it')">
        <i>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </i>
      </xsl:when>
      <xsl:when test="contains($rend, 'bold') or contains($rend, 'gras')">
        <b>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </b>
      </xsl:when>
      <xsl:when test="starts-with($rend, 'ind')">
        <sub>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </sub>
      </xsl:when>
      <xsl:when test="starts-with($rend, 'exp')">
        <sup>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </sup>
      </xsl:when>
      <!-- surlignages venus de la transformation ODT -->
      <xsl:when test="$rend='bg' or $rend='mark'">
        <span class="bg" style="background-color:#{@n};">
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </span>
      </xsl:when>
      <xsl:when test="$rend='col' or $rend='color'">
        <span class="col" style="color:#{@n};">
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </span>
      </xsl:when>
      <!-- sinon appeler le span général -->
      <xsl:otherwise>
        <span>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </span>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- <code>, code en police à chasse fixe -->
  <xsl:template match="tei:code">
    <xsl:call-template name="span">
      <xsl:with-param name="el">code</xsl:with-param>
    </xsl:call-template>
  </xsl:template>
  <xsl:template match="tei:u">
    <xsl:call-template name="span"/>
  </xsl:template>
  <!-- <eg>, exemple en ligne -->
  <!--
  <xsl:template match="tei:p/tei:eg">
    <xsl:call-template name="span">
      <xsl:with-param name="el">samp</xsl:with-param>
    </xsl:call-template>
  </xsl:template>
  -->
  <!-- <gi>, nom d’un élément -->
  <!-- ajout de < et > pas assez robuste en CSS pour utiliser template span -->
  <xsl:template match="tei:gi">
    <code>
      <xsl:text>&lt;</xsl:text>
      <xsl:apply-templates/>
      <xsl:text>&gt;</xsl:text>
    </code>
  </xsl:template>
  <!-- XML sample -->
  <xsl:template match="eg:egXML">
    <xsl:choose>
      <xsl:when test="*">
        <div class="xml">
          <xsl:apply-templates mode="xml2html"/>
        </div>
      </xsl:when>
      <xsl:otherwise>
        <pre class="prettyprint linenums">
          <code class="language-xml">
            <xsl:apply-templates/>
          </code>
        </pre>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!--
    use the @scheme for a link ? TEI, Docbook…

    -->
  <xsl:template match="tei:tag">
    <code class="language-xml prettyprint">
      <xsl:choose>
        <xsl:when test="@type='start'">&lt;</xsl:when>
        <xsl:when test="@type='end'">&lt;/</xsl:when>
        <xsl:when test="@type='empty'">&lt;</xsl:when>
        <xsl:when test="@type='pi'">&lt;?</xsl:when>
        <xsl:when test="@type='comment'">&lt;!--</xsl:when>
        <xsl:when test="@type='pi'">&lt;[CDATA[</xsl:when>
      </xsl:choose>
      <xsl:apply-templates/>
      <xsl:choose>
        <xsl:when test="@type='start'">&gt;</xsl:when>
        <xsl:when test="@type='end'">&gt;</xsl:when>
        <xsl:when test="@type='empty'">/&gt;</xsl:when>
        <xsl:when test="@type='pi'">?&gt;</xsl:when>
        <xsl:when test="@type='comment'">--&gt;</xsl:when>
        <xsl:when test="@type='pi'">]]&gt;</xsl:when>
      </xsl:choose>
    </code>
  </xsl:template>
  <!-- <num>, nombres, utile pour les chiffres romains -->
  <xsl:template match="tei:num">
    <xsl:call-template name="span"/>
  </xsl:template>
  <!-- Césure, html5 <wbr> ? -->
  <xsl:template match="tei:caes">
    <xsl:apply-templates/>
  </xsl:template>
  <!-- Page break in titlePage, something to do, don't know what for now -->
  <xsl:template match="tei:titlePage/tei:pb"/>
  <!-- Page break, add a space, hyphenation supposed to be resolved -->
  <xsl:template match="tei:pb" name="pb">
    <xsl:variable name="norm" select="normalize-space(@n)"/>
    <xsl:variable name="text">
      <xsl:choose>
        <xsl:when test="starts-with(@ed, 'frantext')"/>
        <xsl:when test="$norm = ''"/>
        <xsl:when test="contains('[({', substring($norm, 1,1))">
          <xsl:value-of select="$norm"/>
        </xsl:when>
        <xsl:when test="@ana">[<xsl:value-of select="@ana"/>]</xsl:when>
        <xsl:when test="@ed">[<xsl:value-of select="@n"/>]</xsl:when>
        <!-- number display as a page -->
        <xsl:when test="@n != ''  and contains('0123456789IVXDCM', substring(@n,1,1))">[p. <xsl:value-of select="@n"/>]</xsl:when>
        <xsl:otherwise>[<xsl:value-of select="@n"/>]</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:variable name="class">
      <xsl:text>pb</xsl:text>
      <xsl:if test="@ed">
        <xsl:text> ed </xsl:text>
        <xsl:value-of select="@ed"/>
      </xsl:if>
      <xsl:if test="@facs">
        <xsl:text> facs</xsl:text>
      </xsl:if>
    </xsl:variable>
    <xsl:variable name="id">
      <xsl:call-template name="id"/>
    </xsl:variable>
    <!-- test if inside mixed content, before adding a space, to keep automatic indent  -->
    <xsl:variable name="mixed" select="../text()[normalize-space(.) != '']"/>
    <xsl:choose>
      <xsl:when test="normalize-space(@n) = ''"/>
      <xsl:when test="$text =''"/>
      <xsl:when test="$format = $epub2">
        <xsl:if test="$mixed != ''">
          <xsl:text> </xsl:text>
        </xsl:if>
        <sub>
          <xsl:if test="$id != ''">
            <xsl:attribute name="id">
              <xsl:value-of select="$id"/>
            </xsl:attribute>
          </xsl:if>
        </sub>
        <xsl:if test="$mixed != ''">
          <xsl:text> </xsl:text>
        </xsl:if>
      </xsl:when>
      <xsl:otherwise>
        <xsl:if test="$mixed != ''">
          <xsl:value-of select="$lf"/>
        </xsl:if>
        <xsl:processing-instruction name="index_off"/>
        <a class="{normalize-space($class)}">
          <xsl:choose>
            <!-- @xml:base ? -->
            <xsl:when test="@facs">
              <xsl:attribute name="href">
                <xsl:if test="not(starts-with(@facs, 'http')) and /*/@xml:base">
                  <xsl:value-of select="/*/@xml:base"/>
                </xsl:if>
                <xsl:value-of select="@facs"/>
              </xsl:attribute>
            </xsl:when>
            <xsl:when test="$id != ''">
              <xsl:attribute name="href">
                <xsl:text>#</xsl:text>
                <xsl:value-of select="$id"/>
              </xsl:attribute>
            </xsl:when>
          </xsl:choose>
          <xsl:if test="$id != ''">
            <xsl:attribute name="id">
              <xsl:value-of select="$id"/>
            </xsl:attribute>
          </xsl:if>
          <!-- TODO by js
          <xsl:if test="not(@ed)">
            <xsl:attribute name="onclick">if(window.pb) pb(this, '<xsl:value-of select="$docid"/>', '<xsl:value-of select="@n"/>');</xsl:attribute>
          </xsl:if>
          -->
          <xsl:value-of select="$text"/>
        </a>
        <xsl:processing-instruction name="index_on"/>
        <xsl:if test="$mixed != ''">
          <xsl:value-of select="$lf"/>
        </xsl:if>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- colomn break -->
  <xsl:template match="tei:cb" name="cb">
    <!--
    <xsl:if test="@n and @n != ''">
      <i class="cb"> (<xsl:value-of select="@n"/>) </i>
    </xsl:if>
    -->
  </xsl:template>
  <!-- line breaks -->
  <xsl:template match="tei:lb">
    <xsl:choose>
      <xsl:when test="@n and ancestor::tei:p">
        <xsl:text> </xsl:text>
        <small class="l">[l. <xsl:value-of select="@n"/>]</small>
        <xsl:text> </xsl:text>
      </xsl:when>
      <xsl:when test="@n">
        <small class="lb">
          <xsl:text>
  </xsl:text>
          <br/>
          <xsl:value-of select="@n"/>
          <xsl:text> </xsl:text>
        </small>
      </xsl:when>
      <xsl:otherwise>
        <br>
          <xsl:call-template name="atts"/>
        </br>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- folios, or rules -->
  <xsl:template match="tei:milestone">
    <xsl:choose>
      <xsl:when test="@unit='hr' or @unit='rule'">
        <hr>
          <xsl:call-template name="atts"/>
        </hr>
      </xsl:when>
      <xsl:when test="@unit='folio'">
        <small class="pb folio">
          <xsl:text>f. </xsl:text>
          <xsl:value-of select="@n"/>
        </small>
      </xsl:when>
      <xsl:otherwise>
        <span>
          <xsl:call-template name="atts"/>
          <xsl:value-of select="@n"/>
        </span>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- bloc hors flux -->
  <xsl:template match="tei:fw">
    <xsl:call-template name="span">
      <xsl:with-param name="el">small</xsl:with-param>
    </xsl:call-template>
  </xsl:template>
  <!-- HTML5, “identifiant” -->
  <xsl:template match="tei:ident">
    <xsl:call-template name="span">
      <xsl:with-param name="el">b</xsl:with-param>
    </xsl:call-template>
  </xsl:template>
  <!-- Create an html5 inline element, differents tests done to ensure quality of output -->
  <xsl:template match="tei:institution | tei:heraldry | tei:locus | tei:metamark | tei:nameLink | tei:biblFull/tei:publicationStmt/tei:date | tei:biblFull/tei:publicationStmt/tei:pubPlace | tei:repository | tei:settlement " name="span">
    <xsl:param name="el">span</xsl:param>
    <xsl:choose>
      <xsl:when test=".=''"/>
      <!-- bad tag, with spaces only, tested, not too expensive -->
      <xsl:when test="translate(.,'  ,:;.','')=''">
        <xsl:apply-templates/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- generic inline catch a lot, to put some  -->
  <xsl:template match="tei:distinct | tei:emph | tei:mentioned | tei:sic | tei:soCalled ">
    <xsl:call-template name="span">
      <xsl:with-param name="el">em</xsl:with-param>
    </xsl:call-template>
  </xsl:template>
  <!-- <span class="..."/>, segment générique sans mise en forme particulière -->
  <xsl:template match="tei:phr ">
    <xsl:call-template name="span"/>
  </xsl:template>
  <!-- Go throw with no html effect -->
  <xsl:template match="tei:seg | tei:imprint |  tei:pubPlace | tei:glyph ">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="tei:seg[@rend] | tei:seg[@type]">
    <span>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </span>
  </xsl:template>
  <!-- dates -->
  <xsl:template match="tei:date | tei:publicationStmt/tei:date | tei:docDate | tei:origDate">
    <xsl:variable name="el">
      <xsl:choose>
        <xsl:when test="parent::tei:div">div</xsl:when>
        <xsl:when test="parent::tei:titlePage">p</xsl:when>
        <xsl:when test="parent::tei:front">p</xsl:when>
        <!-- bug in LibreOffice
        <xsl:when test="$format=$html5">time</xsl:when>
        -->
        <xsl:otherwise>span</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:variable name="att">
      <xsl:choose>
        <xsl:when test="$format=$html5">datetime</xsl:when>
        <xsl:otherwise>title</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:variable name="value">
      <xsl:if test="@cert">~</xsl:if>
      <xsl:if test="@scope">~</xsl:if>
      <xsl:variable name="notBefore">
        <xsl:value-of select="number(substring(@notBefore, 1, 4))"/>
      </xsl:variable>
      <xsl:variable name="notAfter">
        <xsl:value-of select="number(substring(@notAfter, 1, 4))"/>
      </xsl:variable>
      <xsl:variable name="when">
        <xsl:value-of select="number(substring(@when, 1, 4))"/>
      </xsl:variable>
      <xsl:choose>
        <xsl:when test="$when != 'NaN'">
          <xsl:value-of select="$when"/>
        </xsl:when>
        <xsl:when test="$notAfter = $notBefore and $notAfter != 'NaN'">
          <xsl:value-of select="$notAfter"/>
        </xsl:when>
        <xsl:when test="$notBefore != 'NaN' and $notAfter != 'NaN'">
          <xsl:value-of select="$notBefore"/>
          <xsl:text>/</xsl:text>
          <xsl:value-of select="$notAfter"/>
        </xsl:when>
        <xsl:when test="$notBefore != 'NaN'">
          <xsl:value-of select="$notBefore"/>
          <xsl:text>/…</xsl:text>
        </xsl:when>
        <xsl:when test="$notAfter != 'NaN'">
          <xsl:text>…–</xsl:text>
          <xsl:value-of select="$notAfter"/>
        </xsl:when>
      </xsl:choose>
    </xsl:variable>
    <xsl:choose>
      <xsl:when test=". = '' and $value = ''"/>
      <xsl:when test=". = '' and $value != ''">
        <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="atts"/>
          <xsl:attribute name="{$att}">
            <xsl:value-of select="$value"/>
          </xsl:attribute>
          <xsl:value-of select="$value"/>
        </xsl:element>
      </xsl:when>
      <xsl:otherwise>
        <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="atts"/>
          <xsl:if test="$value != ''">
            <xsl:attribute name="{$att}">
              <xsl:value-of select="$value"/>
            </xsl:attribute>
          </xsl:if>
          <xsl:apply-templates/>
        </xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- caractère particulier, par exemple lettrine, peut nécessiter une police particulière -->
  <xsl:template match="tei:c">
    <span>
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </span>
  </xsl:template>
  <!--
<h3>Links</h3>
  -->
  <!--   -->
  <xsl:template match="tei:ref">
    <xsl:variable name="html">
      <xsl:apply-templates/>
    </xsl:variable>
    <xsl:choose>
      <!-- bad link pb, seen in notes -->
      <xsl:when test="normalize-space($html) = ''"/>
      <xsl:otherwise>
        <a>
          <xsl:call-template name="atts"/>
          <xsl:copy-of select="$html"/>
        </a>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Ancre -->
  <xsl:template match="tei:anchor">
    <!-- empty char, hack for some readers -->
    <a id="{@xml:id}">&#x200c;</a>
  </xsl:template>
  <!-- email -->
  <xsl:template match="tei:email">
    <xsl:choose>
      <xsl:when test="$format=$epub2">
        <a href="mailto:{normalize-space(.)}">
          <xsl:value-of select="normalize-space(.)"/>
        </a>
      </xsl:when>
      <!-- hide email for publication on web -->
      <xsl:otherwise>
        <xsl:variable name="string">
          <xsl:value-of select="substring-before(., '@')"/>
          <xsl:text>'+'\x40'+'</xsl:text>
          <xsl:value-of select="substring-after(., '@')"/>
        </xsl:variable>
        <xsl:text>&lt;</xsl:text>
        <a>
          <xsl:call-template name="atts"/>
          <xsl:attribute name="href">#</xsl:attribute>
          <xsl:attribute name="onmouseover">if(this.ok) return; this.href='mailto:<xsl:value-of select="$string"/>'; this.ok=true; </xsl:attribute>
          <xsl:text>&#x200c;</xsl:text>
          <xsl:value-of select="substring-before(., '@')"/>
          <xsl:text>&#x200c;</xsl:text>
          <xsl:text>＠</xsl:text>
          <xsl:text>&#x200c;</xsl:text>
          <xsl:value-of select="substring-after(., '@')"/>
          <xsl:text>&#x200c;</xsl:text>
        </a>
        <xsl:text>&gt;</xsl:text>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Pointeur sans label -->
  <xsl:template match="tei:ptr">
    <a>
      <xsl:call-template name="atts"/>
      <xsl:attribute name="href">
        <xsl:value-of select="@target"/>
      </xsl:attribute>
      <xsl:value-of select="@target"/>
    </a>
  </xsl:template>
  <!-- Quelque chose à faire ?

<figure>
  <graphic url="../../../../elec/conferences/src/knoch-mund/olgiati.png"/>
  <head>© Mirta Olgiati</head>
</figure>

<figure class="right">
  <a href="ferri/grande/Ill_2_Grillando.jpg"><img src="ferri/petite/Ill_2_Grillando.jpg" height="155" width="250"></a>
  <figcaption style="width: 250px;">
    <a href="ferri/grande/Ill_2_Grillando.jpg"><img src="theme/img/agrandir.png" title="agrandir"></a>
    [ill. 1] Paolo Grillando, Tractat[um] de hereticis et sortilegijs…, Lyon, 1536 [page de titre]. Cornell Library, Division of Rare Books and Manuscripts, Witchcraft BF1565 G85 1536.
    </figcaption>
</figure>

  -->
  <xsl:template match="tei:figure | tei:facsimile">
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="$format = 'html5'">figure</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
      <xsl:call-template name="atts"/>
      <!-- ?
      <a class="bookmark">
        <xsl:attribute name="href">
          <xsl:call-template name="href"/>
        </xsl:attribute>
        <xsl:text> §</xsl:text>
      </a>
      -->
      <xsl:apply-templates/>
    </xsl:element>
  </xsl:template>
  <xsl:template match="tei:figure/tei:figDesc | tei:figure/tei:desc | tei:figure/tei:head">
    <xsl:choose>
      <xsl:when test=".=''"/>
      <xsl:when test="$format = 'html5'">
        <figcaption>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </figcaption>
      </xsl:when>
      <xsl:otherwise>
        <div>
          <xsl:call-template name="atts">
            <xsl:with-param name="class">figcaption</xsl:with-param>
          </xsl:call-template>
          <xsl:apply-templates/>
        </div>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Images, TODO @alt -->
  <xsl:template match="tei:graphic">
    <xsl:variable name="id">
      <xsl:call-template name="id"/>
    </xsl:variable>
    <img src="{$images}{@url}" alt="{normalize-space(.)}" id="{$id}">
      <xsl:if test="@style|@scale">
        <xsl:variable name="style">
          <xsl:if test="@scale &gt; 0 and @scale &lt; 1">
            <xsl:text>width: </xsl:text>
            <xsl:value-of select="floor(@scale * 100)"/>
            <xsl:text>%; </xsl:text>
          </xsl:if>
          <xsl:value-of select="@style"/>
        </xsl:variable>
        <xsl:attribute name="style">
          <xsl:value-of select="normalize-space($style)"/>
        </xsl:attribute>
      </xsl:if>
      <xsl:if test="@rend">
        <xsl:attribute name="class">
          <xsl:value-of select="@rend"/>
        </xsl:attribute>
      </xsl:if>
    </img>
  </xsl:template>
  <!-- Sentence -->
  <xsl:template match="tei:s">
    <xsl:text>
</xsl:text>
    <xsl:apply-templates/>
  </xsl:template>
  <!-- <w>, word, avec possible définition -->
  <xsl:template match="tei:w">
    <xsl:variable name="def" select="key('id', substring(@lemmaRef, 2))"/>
    <xsl:variable name="lastW" select="preceding-sibling::tei:w[1]"/>
    <xsl:variable name="lastChar" select="substring($lastW, string-length($lastW))"/>
    <xsl:choose>
      <!--  Particulier à MontFerrand, à sortir ? -->
      <xsl:when test="$def">
        <a>
          <xsl:call-template name="atts"/>
          <xsl:attribute name="title">
            <xsl:for-each select="$def/ancestor-or-self::tei:entry[1]//tei:def">
              <xsl:value-of select="normalize-space(.)"/>
              <xsl:choose>
                <xsl:when test="position() != last()"> ; </xsl:when>
                <xsl:otherwise>.</xsl:otherwise>
              </xsl:choose>
            </xsl:for-each>
          </xsl:attribute>
          <xsl:apply-templates/>
        </a>
      </xsl:when>
      <!-- french PUNCT ? -->
      <xsl:when test="string-length(.)=1 and contains(';:?!»', .)">
        <xsl:text> </xsl:text>
        <xsl:value-of select="."/>
      </xsl:when>
      <xsl:when test="string-length(.)=1 and contains('),.', .)">
        <xsl:value-of select="."/>
      </xsl:when>
      <xsl:when test="string-length(.)=1 and contains('),.', .)">
        <xsl:value-of select="."/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="pos">
          <xsl:number/>
        </xsl:variable>
        <xsl:if test="$pos!=1 and $lastChar!='’' and $lastChar!=$apos">
          <xsl:text> </xsl:text>
        </xsl:if>
        <xsl:apply-templates/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Do something on punctuation ? -->
  <xsl:template match="tei:pc | tei:g">
    <xsl:apply-templates/>
  </xsl:template>
  <!-- behaviors on text nodes between words  -->
  <!--
  <xsl:template match="text()">
    <xsl:choose>
      <xsl:when test="normalize-space(.)='' and ../tei:w"/>
      <xsl:when test="$format = $epub2 and not(../tei:w)">
        <xsl:value-of select="translate(., '‘’★‑∾ ', concat($apos, $apos,'*-~ '))"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="."/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  -->
  <!-- Poetic information, rendering? -->
  <xsl:template match="tei:caesura">
    <xsl:text> </xsl:text>
  </xsl:template>
  <!-- Cross without a trace? -->
  <xsl:template match="tei:corr">
    <xsl:choose>
      <xsl:when test="not(../tei:w)"/>
      <xsl:when test="preceding-sibling::*[1][self::tei:w]">
        <xsl:text> </xsl:text>
      </xsl:when>
    </xsl:choose>
    <xsl:apply-templates/>
  </xsl:template>
  <!--
<h3>Bibliographie</h3>

  -->
  <xsl:template match="tei:biblFull | tei:biblStruct | tei:msDesc | tei:msPart | tei:witness">
    <xsl:variable name="el">
      <xsl:choose>
        <!-- Référence bibliographique affichée comme bloc -->
        <xsl:when test="parent::tei:accMat or parent::tei:cit or parent::tei:div or parent::tei:div0 or parent::tei:div1 or parent::tei:div2 or parent::tei:div3 or parent::tei:div4 or parent::tei:div5 or parent::tei:div6 or parent::tei:div7 or parent::tei:epigraph">p</xsl:when>
        <xsl:when test="parent::tei:listWit or parent::tei:listBibl">li</xsl:when>
        <xsl:otherwise>span</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:call-template name="atts"/>
      <xsl:if test="@n">
        <small class="n">
          <xsl:value-of select="@n"/>
        </small>
        <xsl:text> </xsl:text>
      </xsl:if>
      <xsl:if test="@xml:id">
        <i>
          <xsl:call-template name="witid"/>
        </i>
        <xsl:text> </xsl:text>
      </xsl:if>
      <xsl:choose>
        <xsl:when test="self::tei:biblStruct">
          <xsl:for-each select="*">
            <xsl:apply-templates select="."/>
            <xsl:choose>
              <xsl:when test="position()=last()">.</xsl:when>
              <xsl:otherwise>. </xsl:otherwise>
            </xsl:choose>
          </xsl:for-each>
        </xsl:when>
        <xsl:otherwise>
          <xsl:apply-templates/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:element>
  </xsl:template>
  <!-- Traverser sans trace -->
  <xsl:template match="tei:monogr | tei:analytic">
    <xsl:apply-templates/>
  </xsl:template>
  <!-- Manuscript ou imprimé, notamment appelé comme témoin -->
  <xsl:template match="tei:msDesc | tei:bibl" mode="a">
    <a class="{local-name()}">
      <xsl:attribute name="href">
        <xsl:call-template name="href"/>
      </xsl:attribute>
      <xsl:variable name="title">
        <xsl:apply-templates/>
      </xsl:variable>
      <xsl:attribute name="title">
        <xsl:value-of select="normalize-space($title)"/>
      </xsl:attribute>
      <xsl:choose>
        <xsl:when test="@xml:id">
          <xsl:call-template name="witid"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:call-template name="message"/>
        </xsl:otherwise>
      </xsl:choose>
    </a>
  </xsl:template>
  <!-- Présentation d'identifiants de témoins -->
  <xsl:template name="witid">
    <xsl:param name="s" select="@xml:id"/>
    <xsl:choose>
      <xsl:when test="normalize-space($s)=''"/>
      <xsl:when test="contains($s, '_') or contains($s, '.')">
        <xsl:value-of select="."/>
      </xsl:when>
      <!-- Si patron normalisé d'identifiant pour édition -->
      <xsl:when test="starts-with($s, 'ed') and translate(substring($s,3), '0123456789 ', '')=''">
        <xsl:text>éd. </xsl:text>
        <xsl:value-of select="normalize-space(substring($s,3))"/>
      </xsl:when>
      <xsl:when test="translate(substring($s, 1, 1), 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz', '') =''">
        <xsl:value-of select="substring($s, 1, 1)"/>
        <xsl:call-template name="witid">
          <xsl:with-param name="s" select="substring($s, 2)"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <sup>
          <xsl:value-of select="$s"/>
        </sup>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:title">
    <xsl:choose>
      <xsl:when test="@ref">
        <!-- ?? resolve links ? -->
        <a href="{@ref}">
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </a>
      </xsl:when>
      <xsl:otherwise>
        <cite>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </cite>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- titre avec une URI pas loin -->
  <xsl:template match="tei:title[../tei:idno[@type='URI']]">
    <a href="{../tei:idno[@type='URI']}">
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </a>
  </xsl:template>
  <!-- na pas sortir l'URI -->
  <xsl:template match="tei:idno[@type='URI'][../tei:title]" priority="5"/>
  <!-- identifiant bibliographique -->
  <xsl:template match="tei:idno | tei:altIdentifier">
    <xsl:choose>
      <xsl:when test="starts-with(normalize-space(.), 'http')">
        <a>
          <xsl:call-template name="atts"/>
          <xsl:attribute name="href">
            <xsl:value-of select="normalize-space(.)"/>
          </xsl:attribute>
          <xsl:choose>
            <xsl:when test="@type and @type != 'URI'">
              <xsl:value-of select="@type"/>
            </xsl:when>
            <xsl:otherwise>
              <xsl:apply-templates/>
            </xsl:otherwise>
          </xsl:choose>
        </a>
      </xsl:when>
      <xsl:otherwise>
        <span>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </span>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:biblScope | tei:collation | tei:collection | tei:dim | tei:docAuthor | tei:editor | tei:edition | tei:extent | tei:funder | tei:publisher | tei:stamp | tei:biblFull/tei:titleStmt/tei:title">
    <xsl:variable name="element">
      <xsl:choose>
        <xsl:when test="parent::tei:titlePage">p</xsl:when>
        <xsl:when test="parent::tei:front">p</xsl:when>
        <xsl:when test="parent::tei:div">p</xsl:when>
        <xsl:otherwise>span</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$element}">
      <xsl:call-template name="atts"/>
      <xsl:apply-templates/>
    </xsl:element>
  </xsl:template>
  <!-- generic div -->
  <xsl:template match="tei:accMat | tei:additional | tei:additions | tei:addrLine | tei:adminInfo | tei:binding | tei:bindingDesc | tei:closer | tei:condition | tei:equiv | tei:history | tei:licence | tei:listRef | tei:objectDesc | tei:origin | tei:provenance | tei:physDesc | tei:biblFull/tei:publicationStmt | tei:source | tei:speaker | tei:support | tei:supportDesc | tei:surrogates | tei:biblFull/tei:titleStmt" name="div">
    <xsl:param name="el">
      <xsl:choose>
        <xsl:when test="self::tei:speaker">p</xsl:when>
        <xsl:otherwise>div</xsl:otherwise>
      </xsl:choose>
    </xsl:param>
    <xsl:if test="normalize-space(.) != ''">
      <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
        <xsl:call-template name="atts"/>
        <xsl:apply-templates/>
      </xsl:element>
    </xsl:if>
  </xsl:template>
  <xsl:template match="tei:handDesc">
    <ul>
      <xsl:for-each select="*">
        <li>
          <xsl:call-template name="atts"/>
          <xsl:if test="@xml:id">
            <small class="id">[<xsl:value-of select="@xml:id"/>]</small>
            <xsl:text> </xsl:text>
          </xsl:if>
          <xsl:apply-templates/>
        </li>
      </xsl:for-each>
    </ul>
  </xsl:template>
  <xsl:template match="tei:dimensions">
    <span>
      <xsl:call-template name="atts"/>
      <xsl:variable name="message">
        <xsl:call-template name="message">
          <xsl:with-param name="id">
            <xsl:value-of select="@type"/>
          </xsl:with-param>
        </xsl:call-template>
      </xsl:variable>
      <xsl:if test="$message != ''">
        <xsl:value-of select="translate(substring($message, 1, 1), $lc, $uc)"/>
        <xsl:value-of select="substring($message, 2)"/>
        <xsl:text> : </xsl:text>
      </xsl:if>
      <xsl:for-each select="*">
        <xsl:apply-templates select="."/>
        <xsl:choose>
          <xsl:when test="position() =last()"/>
          <xsl:otherwise> x </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
      <xsl:if test="@unit">
        <xsl:text> </xsl:text>
        <xsl:value-of select="@unit"/>
      </xsl:if>
      <xsl:text>.</xsl:text>
    </span>
  </xsl:template>
  <xsl:template match="tei:layout">
    <div>
      <xsl:call-template name="atts"/>
      <xsl:choose>
        <xsl:when test="@columns and number(@columns) &gt; 1"> &lt;TODO&gt; </xsl:when>
        <xsl:when test="@ruledLines">
          <xsl:value-of select="@ruledLines"/>
          <xsl:text> </xsl:text>
          <xsl:call-template name="message">
            <xsl:with-param name="id">ruledLines</xsl:with-param>
          </xsl:call-template>
          <xsl:text>, </xsl:text>
          <xsl:value-of select="@writtenLines"/>
          <xsl:call-template name="message">
            <xsl:with-param name="id">writtenLines</xsl:with-param>
          </xsl:call-template>
          <xsl:text>, </xsl:text>
        </xsl:when>
        <xsl:when test="@writtenLines">
          <xsl:value-of select="@writtenLines"/>
          <xsl:text> </xsl:text>
          <xsl:call-template name="message">
            <xsl:with-param name="id">lines</xsl:with-param>
          </xsl:call-template>
          <xsl:text>, </xsl:text>
        </xsl:when>
      </xsl:choose>
      <xsl:variable name="text">
        <xsl:apply-templates/>
      </xsl:variable>
      <xsl:copy-of select="$text"/>
      <xsl:if test="translate(substring(normalize-space($text), string-length(normalize-space($text))), '.?!', '') != ''">.</xsl:if>
    </div>
  </xsl:template>
  <!-- Go throw -->
  <xsl:template match="tei:height | tei:width | tei:layoutDesc ">
    <xsl:apply-templates/>
  </xsl:template>
  <xsl:template match="tei:msIdentifier">
    <span>
      <xsl:call-template name="atts"/>
      <xsl:for-each select="*">
        <xsl:apply-templates select="."/>
        <xsl:choose>
          <xsl:when test="position() = last()">. </xsl:when>
          <xsl:otherwise>
            <xsl:text>, </xsl:text>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </span>
  </xsl:template>
  <!-- Indication du témoin édité (mention "D'après...") -->
  <xsl:template match="tei:witness[@ana='edited']" mode="according">
    <div class="according">
      <xsl:text>D'après </xsl:text>
      <xsl:value-of select="@n"/>
      <xsl:text>.</xsl:text>
    </div>
  </xsl:template>
  <!-- Affichage de n° de témoin -->
  <xsl:template match="tei:witness" mode="a">
    <a class="wit">
      <xsl:attribute name="href">
        <xsl:call-template name="href"/>
      </xsl:attribute>
      <xsl:attribute name="title">
        <xsl:value-of select="normalize-space(.)"/>
      </xsl:attribute>
      <xsl:choose>
        <xsl:when test="tei:label">
          <xsl:apply-templates select="tei:label/node()"/>
        </xsl:when>
        <xsl:when test="@n">
          <xsl:value-of select="@n"/>
        </xsl:when>
        <xsl:when test="@xml:id">
          <xsl:value-of select="@xml:id"/>
        </xsl:when>
      </xsl:choose>
    </a>
  </xsl:template>
  <!-- Lien vers un groupe de témoins -->
  <xsl:template match="tei:listWit" mode="a">
    <a class="wit">
      <xsl:attribute name="href">
        <xsl:call-template name="href"/>
      </xsl:attribute>
      <xsl:for-each select="tei:witness">
        <xsl:variable name="label">
          <xsl:apply-templates select="."/>
        </xsl:variable>
        <xsl:value-of select="$label"/>
        <xsl:choose>
          <xsl:when test="position() = last()">.</xsl:when>
          <xsl:otherwise>, </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </a>
  </xsl:template>
  <!--
Elements block or inline level
   -->
  <xsl:template match="tei:bibl | tei:gloss | tei:label | tei:q | tei:quote | tei:said | tei:stage">
    <xsl:variable name="mixed" select="../text()[normalize-space(.) != '']"/>
    <xsl:choose>
      <xsl:when test="normalize-space(.) = ''">
        <xsl:apply-templates/>
      </xsl:when>
      <!-- inside mixed content, or line formatted text (? or tei:lb or ../tei:lb), should be inline -->
      <xsl:when test="$mixed  or parent::tei:note or parent::tei:p or parent::tei:s  or parent::tei:label">
        <xsl:call-template name="span">
          <xsl:with-param name="el">
            <xsl:choose>
              <xsl:when test="self::tei:gloss">dfn</xsl:when>
              <xsl:when test="self::tei:label">b</xsl:when>
              <xsl:when test="self::tei:stage">em</xsl:when>
              <xsl:when test="self::tei:q">q</xsl:when>
              <xsl:when test="self::tei:quote">q</xsl:when>
              <xsl:when test="self::tei:said">q</xsl:when>
              <xsl:otherwise>span</xsl:otherwise>
            </xsl:choose>
          </xsl:with-param>
        </xsl:call-template>
      </xsl:when>
      <xsl:when test="@corresp and contains(@type, 'embed') and $format != $epub2">
        <figure>
          <xsl:call-template name="atts">
            <xsl:with-param name="class">corresp</xsl:with-param>
          </xsl:call-template>
          <figcaption>
            <xsl:call-template name="atts"/>
            <xsl:attribute name="xml:base">
              <xsl:value-of select="@corresp"/>
            </xsl:attribute>
            <xsl:apply-templates/>
          </figcaption>
        </figure>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="el">
          <xsl:choose>
            <xsl:when test="self::tei:label and parent::tei:figure">div</xsl:when>
            <xsl:when test="self::tei:label">p</xsl:when>
            <xsl:when test="self::tei:quote">blockquote</xsl:when>
            <xsl:otherwise>div</xsl:otherwise>
          </xsl:choose>
        </xsl:variable>
        <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
          <xsl:call-template name="atts">
            <xsl:with-param name="class">
              <xsl:if test="@corresp"> corresp</xsl:if>
            </xsl:with-param>
          </xsl:call-template>
          <xsl:apply-templates/>
        </xsl:element>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <xsl:template match="tei:cit">
    <xsl:variable name="mixed" select="../text()[normalize-space(.) != '']"/>
    <xsl:choose>
      <xsl:when test="$mixed">
        <span>
          <xsl:call-template name="atts"/>
          <xsl:for-each select="*">
            <xsl:if test="position() != 1">
              <xsl:text> </xsl:text>
            </xsl:if>
            <xsl:apply-templates select="."/>
          </xsl:for-each>
        </span>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="div"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!--
<h3>Indexables</h3>
  -->
  <!-- kindle support @id & @title only on <em> (but no class !!)  -->
  <xsl:template match="tei:term">
    <xsl:choose>
      <xsl:when test=". = ''"/>
      <xsl:otherwise>
        <dfn>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </dfn>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Default, content from <index> is supposed to not be displayed -->
  <xsl:template match="tei:index"/>
  <!-- Termes with possible normal form -->
  <xsl:template match="tei:addName | tei:affiliation | tei:author | tei:authority | tei:country | tei:foreign | tei:forename | tei:genName | tei:geogFeat | tei:geogName | tei:name | tei:origPlace | tei:orgName | tei:persName | tei:placeName | tei:repository | tei:roleName | tei:rs | tei:settlement | tei:surname">
    <xsl:choose>
      <!-- empty -->
      <xsl:when test="normalize-space(.) = ''">
        <xsl:apply-templates/>
      </xsl:when>
      <!-- ?? name as a margin note ? But what is in flow ? -->
      <!--
      <xsl:when test="@rend = 'margin'">
        <div class="marginalia">
          <xsl:if test=". = '' and @key">
            <xsl:if test="@role != ''">
              <xsl:call-template name="message">
                <xsl:with-param name="id" select="@role"/>
              </xsl:call-template>
              <xsl:text> : </xsl:text>
            </xsl:if>
            <xsl:value-of select="@key"/>
          </xsl:if>
          <xsl:apply-templates/>
        </div>
      </xsl:when>
      -->
      <xsl:when test="@ref or @xml:base">
        <a>
          <!-- linking policy will be resolved from the "linking" template, matched by @ref attribute -->
          <xsl:call-template name="atts"/>
          <!-- Make an exception here for @xml:base ? -->
          <xsl:choose>
            <xsl:when test="self::tei:persName">
              <xsl:attribute name="property">dbo:person</xsl:attribute>
            </xsl:when>
            <xsl:when test="self::tei:placeName">
              <xsl:attribute name="property">dbo:place</xsl:attribute>
            </xsl:when>
          </xsl:choose>
          <xsl:apply-templates/>
        </a>
      </xsl:when>
      <xsl:otherwise>
        <span>
          <xsl:call-template name="atts"/>
          <xsl:apply-templates/>
        </span>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!--
Attributes

Centralize some html attribute policy, especially for id, and class
  -->
  <xsl:template name="atts">
    <!-- Add some html classes to the automatic ones -->
    <xsl:param name="class"/>
    <!-- Ddelegate class attribution to another template -->
    <xsl:call-template name="class">
      <xsl:with-param name="class" select="$class"/>
    </xsl:call-template>
    <!-- Shall we identify element ? -->
    <xsl:choose>
      <!--
      <xsl:when test="normalize-space(@id) != ''">
        <xsl:attribute name="id">
          <xsl:value-of select="translate(normalize-space(@id), ' ', '')"/>
        </xsl:attribute>
      </xsl:when>
      -->
      <xsl:when test="normalize-space(@xml:id) != ''">
        <xsl:attribute name="id">
          <xsl:value-of select="translate(normalize-space(@xml:id), ' ', '')"/>
        </xsl:attribute>
      </xsl:when>
      <xsl:when test="generate-id(..) = generate-id(/*/tei:text)">
        <xsl:attribute name="id">
          <xsl:call-template name="id"/>
        </xsl:attribute>
      </xsl:when>
    </xsl:choose>
    <!-- Process other know attributes -->
    <xsl:apply-templates select="@*"/>
  </xsl:template>
  <!-- Provide automatic classes from TEI names -->
  <xsl:template name="class">
    <xsl:param name="class"/>
    <!-- @rend may be used as a free text attribute, be careful -->
    <xsl:variable name="value">
      <!-- Name of the element (except from a list where TEI info will be redundant with HTML name) -->
      <xsl:if test="not(contains( ' abbr add cell code del eg emph hi item list q ref row seg table ' , concat(' ', local-name(), ' ')))">
        <xsl:value-of select="local-name()"/>
      </xsl:if>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@type"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@subtype"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@role"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@ana"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@evidence"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@place"/>
      <xsl:text> </xsl:text>
      <!-- lang is a useful class for some rendering (ex: greek fonts) -->
      <xsl:value-of select="@xml:lang"/>
      <xsl:text> </xsl:text>
      <!-- parameter value -->
      <xsl:value-of select="$class"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="@rend"/>
      <xsl:text> </xsl:text>
      <xsl:value-of select="translate(@rendition, '#', '')"/>
    </xsl:variable>
    <xsl:variable name="norm" select="normalize-space($value)"/>
    <xsl:if test="$norm != ''">
      <xsl:attribute name="class">
        <xsl:value-of select="$norm"/>
      </xsl:attribute>
    </xsl:if>
  </xsl:template>
  <!-- by default, do nothing on attribute -->
  <xsl:template match="tei:*/@*" priority="-2"/>
  <!-- Genererate html attributes from  à target attribute -->
  <xsl:template match="@ref | @target | @lemmaRef" name="linking">
    <xsl:param name="path" select="."/>
    <xsl:choose>
      <!-- Que faire avec witDetail/@target ? -->
      <xsl:when test="../@wit"/>
      <!-- Hide mail adress -->
      <xsl:when test="contains($path, '@')">
        <xsl:attribute name="href">#</xsl:attribute>
        <xsl:attribute name="onmouseover">
          <xsl:text>this.href='mailto:</xsl:text>
          <xsl:value-of select="substring-before($path, '@')"/>
          <xsl:text>'+'\x40'+'</xsl:text>
          <xsl:value-of select="substring-after($path, '@')"/>
          <xsl:text>'</xsl:text>
        </xsl:attribute>
      </xsl:when>
      <!-- internal link, should point an existing element (maybe not) -->
      <xsl:when test="starts-with($path, '#') and key('id', substring($path, 2))">
        <xsl:for-each select="key('id', substring($path, 2))[1]">
          <xsl:attribute name="href">
            <xsl:call-template name="href"/>
          </xsl:attribute>
          <xsl:attribute name="title">
            <xsl:call-template name="href"/>
          </xsl:attribute>
        </xsl:for-each>
        <!-- perf mode href ? -->
      </xsl:when>
      <!-- internal link with no # -->
      <xsl:when test="key('id', $path)">
        <!-- perf mode href ? -->
        <xsl:for-each select="key('id', $path)[1]">
          <xsl:attribute name="href">
            <xsl:call-template name="href"/>
          </xsl:attribute>
        </xsl:for-each>
      </xsl:when>
      <xsl:otherwise>
        <xsl:attribute name="href">
          <xsl:value-of select="$path"/>
        </xsl:attribute>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- @id, @lang, réécriture de certains attributs standard pour xhtml -->
  <xsl:template match="@xml:lang | @xml:id">
    <xsl:variable name="id0"> '":,; </xsl:variable>
    <xsl:attribute name="{local-name(.)}">
      <xsl:value-of select="translate(., $id0, '')"/>
    </xsl:attribute>
  </xsl:template>
  <xsl:template match="@style">
    <xsl:attribute name="style">
      <xsl:value-of select="."/>
    </xsl:attribute>
  </xsl:template>
  <!-- @xml:*, attributs à recopier à l'identique -->
  <xsl:template match="@xml:base">
    <xsl:copy-of select="."/>
  </xsl:template>
</xsl:transform>
