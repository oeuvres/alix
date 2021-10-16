<?xml version="1.0" encoding="UTF-8"?>
<!--
TEI to HTML, handling notes and critical apparatus

LGPL  http://www.gnu.org/licenses/lgpl.html
© 2016 Frederic.Glorieux@fictif.org 


-->
<xsl:transform version="1.0"
  exclude-result-prefixes="tei epub" 
  extension-element-prefixes="exslt" 
  xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xmlns:exslt="http://exslt.org/common" xmlns:tei="http://www.tei-c.org/ns/1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Shared templates -->
  <xsl:import href="common.xsl"/>
  <xsl:output indent="yes" encoding="UTF-8" method="xml" />
  <!-- key for notes by page, keep the tricky @use expression in this order, when there are other parallel pages number -->
  <xsl:key name="note-pb" match="tei:note[not(parent::tei:sp)][not(starts-with(local-name(..), 'div'))]" use="generate-id(  preceding::*[self::tei:pb[not(@ed)][@n] ][1]  ) "/>
  <xsl:key name="note-before" match="tei:note[not(parent::tei:sp)][not(starts-with(local-name(..), 'div'))]" use="generate-id(following::*[self::tei:pb[not(@ed)][@n] ][1]  ) "/>
  <!-- Are there apparatus entry ? -->
  <xsl:param name="app" select="boolean(//tei:app)"/>
  <!-- Call this template to get all the notes from the current node with their links -->
  
  <xsl:template name="footnotes">
    <xsl:choose>
      <xsl:when test="function-available('exslt:node-set')">
        <xsl:variable name="notes">
          <xsl:apply-templates mode="fn"/>
        </xsl:variable>
        <xsl:if test="$notes">
          <section class="footnotes">
            <xsl:for-each select="exslt:node-set($notes)">
              <xsl:sort select="@class"/>
              <xsl:copy-of select="."/>
            </xsl:for-each>
          </section>
        </xsl:if>
        <!--
        <xsl:if test="$notes">
          <section class="footnotes">
            <xsl:for-each select="exslt:node-set($notes)">
              <xsl:sort select="@place|@resp"/>
              <xsl:call-template name="note"/>
            </xsl:for-each>
          </section>
        </xsl:if>
        -->
      </xsl:when>
      <xsl:otherwise>
        <xsl:apply-templates mode="fn"/>
      </xsl:otherwise>
    </xsl:choose>
    
  </xsl:template>

  <xsl:template match="text()" mode="fn"/>
  <xsl:template match="*" mode="fn">
    <xsl:apply-templates mode="fn"/>
  </xsl:template>
  
  <xsl:template match="tei:note" mode="fn">
    <xsl:call-template name="note"/>
  </xsl:template>
  

  <xsl:template name="footnotes-old">
    <xsl:param name="from" select="."/>
    <!-- get pages in the section -->
    <xsl:param name="pb" select="$from//tei:pb[@n][not(@ed)]"/>
    <!-- Handle on current node -->
    <xsl:variable name="current" select="."/>
    <xsl:variable name="notes">
      <xsl:if test="$app">
        <xsl:variable name="apparatus">
          <xsl:for-each select="$from// tei:app">
            <xsl:call-template name="note-inline">
              <xsl:with-param name="from" select="$from"/>
            </xsl:call-template>
          </xsl:for-each>
        </xsl:variable>
        <xsl:if test="$apparatus != ''">
          <p class="apparatus">
            <xsl:copy-of select="$apparatus"/>
          </p>
        </xsl:if>
      </xsl:if>
      <!-- Here some xsl processors add an empty ref in notes -->
      <!--
      <xsl:for-each select="$cont //tei:*[@rend='note']">
        <xsl:sort select="local-name()"/>
        <xsl:call-template name="fn"/>
      </xsl:for-each>
      -->
      <xsl:choose>
        <xsl:when test=" $pb ">
          <xsl:variable name="pb1" select="$pb[1]"/>
          <!-- first notes before the first pb, a preceding:: axis was very slow  -->
          <xsl:variable name="notes1" select="key('note-before', generate-id($pb1))"/>
          
          <xsl:if test="count($notes1) &gt; 0">
            <div class="page">
              <xsl:text> </xsl:text>
              <xsl:for-each select="$notes1">
                <xsl:sort select="@place|@resp"/>
                <xsl:choose>
                  <!-- is inside current section -->
                  <xsl:when test="ancestor::*[count(.|$current)=1]">
                    <xsl:call-template name="note"/>
                  </xsl:when>
                </xsl:choose>
              </xsl:for-each>
            </div>
          </xsl:if>
          <xsl:for-each select="$pb">
            <xsl:variable name="notes4page">
              <xsl:for-each select="key('note-pb', generate-id())[@resp = 'author'][not(@place = 'margin')]">
                <!-- do not output notes for a page, outside from the <div> scope  -->
                <xsl:if test="ancestor::*[count(.|$current)=1]">
                  <xsl:call-template name="note"/>
                </xsl:if>
              </xsl:for-each>
              <xsl:for-each select="key('note-pb', generate-id())[@resp = 'editor'][not(@place = 'margin')]">
                <xsl:if test="ancestor::*[count(.|$current)=1]">
                  <xsl:call-template name="note"/>
                </xsl:if>
              </xsl:for-each>
              <xsl:for-each select="key('note-pb', generate-id())[not(@resp) or (@resp != 'editor' and @resp != 'author')][not(@place = 'margin')]">
                <xsl:if test="ancestor::*[count(.|$current)=1]">
                  <xsl:call-template name="note"/>
                </xsl:if>
              </xsl:for-each>
            </xsl:variable>
            <xsl:if test="$notes4page != ''">
              <div class="page">
                <div class="b note-page">
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
                </div>
                <xsl:copy-of select="$notes4page"/>
              </div>
            </xsl:if>
          </xsl:for-each>
        </xsl:when>
        <!-- handle notes by split sections ? -->
        <xsl:otherwise>
          <xsl:for-each select=".//tei:note">
            <xsl:sort select="@place|@resp"/>
            <xsl:call-template name="note"/>
          </xsl:for-each>
          <!-- ???
          <xsl:apply-templates mode="fn" select=".//tei:note">
            <xsl:with-param name="resp">author</xsl:with-param>
          </xsl:apply-templates>
          <xsl:apply-templates mode="fn" select="$nodes//tei:note">
            <xsl:with-param name="resp">editor</xsl:with-param>
          </xsl:apply-templates>
          <xsl:apply-templates mode="fn" select="$nodes//tei:note"/>
          -->
        </xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:if test="$notes != ''">
      <xsl:variable name="el">
        <xsl:choose>
          <xsl:when test="$format=$epub2">div</xsl:when>
          <xsl:otherwise>section</xsl:otherwise>
        </xsl:choose>
      </xsl:variable>
      <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
        <xsl:if test="$format = $epub3">
          <xsl:attribute name="epub:type">footnotes</xsl:attribute>
        </xsl:if>
        <xsl:attribute name="class">footnotes</xsl:attribute>
        <xsl:copy-of select="$notes"/>
      </xsl:element>
    </xsl:if>
  </xsl:template>
  <!-- Inline view for apparatus note -->
  <xsl:template name="note-inline">
    <xsl:param name="from"/>
    <!-- Note identifier -->
    <xsl:variable name="id">
      <xsl:call-template name="id"/>
    </xsl:variable>
    <xsl:variable name="text">
      <xsl:for-each select="text()">
        <xsl:value-of select="normalize-space(.)"/>
      </xsl:for-each>
    </xsl:variable>
    <xsl:variable name="html">
      <xsl:choose>
        <xsl:when test="$text='' and count(*)=1 and tei:p">
          <span class="note" id="{$id}">
            <xsl:if test="$format = $epub3">
              <xsl:attribute name="epub:type">note</xsl:attribute>
            </xsl:if>
            <xsl:call-template name="noteback">
              <xsl:with-param name="class"/>
              <xsl:with-param name="from" select="$from"/>
            </xsl:call-template>
            <xsl:text>, </xsl:text>
            <xsl:apply-templates select="*/node()"/>
          </span>
        </xsl:when>
        <xsl:when test="$text='' and *[1][self::tei:p]">
          <div class="note" id="{$id}">
            <p class="noindent">
              <xsl:call-template name="noteback">
                <xsl:with-param name="from" select="$from"/>
              </xsl:call-template>
              <xsl:apply-templates select="*[1]/node()"/>
            </p>
            <xsl:apply-templates select="*[position() &gt; 1]"/>
          </div>
        </xsl:when>
        <xsl:otherwise>
          <span class="note" id="{$id}">
            <xsl:call-template name="noteback">
              <xsl:with-param name="from" select="$from"/>
              <xsl:with-param name="class"/>
            </xsl:call-template>
            <xsl:apply-templates/>
          </span>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:if test="$html != ''">
      <xsl:copy-of select="$html"/>
      <!-- regarder si la ligne est déjà ponctuée avant d'ajouter un point -->
      <xsl:variable name="norm" select="normalize-space($html)"/>
      <xsl:variable name="last" select="substring($norm, string-length($norm))"/>
      <xsl:choose>
        <!-- block -->
        <xsl:when test="count(*) &gt; 1 and $text='' "/>
        <!-- Déja ponctué -->
        <xsl:when test="$last=',' or $last=';' or $last='.'">
          <xsl:text> </xsl:text>
        </xsl:when>
        <xsl:otherwise>. </xsl:otherwise>
      </xsl:choose>
    </xsl:if>
  </xsl:template>
  <!-- section de notes déjà inscrite dans la source -->
  <xsl:template match="tei:div[@type='notes' or @type='footnotes']">
    <xsl:variable name="el">
      <xsl:choose>
        <xsl:when test="$format=$epub2">div</xsl:when>
        <xsl:otherwise>section</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:attribute name="class">footnotes</xsl:attribute>
      <xsl:attribute name="id">
        <xsl:call-template name="id"/>
      </xsl:attribute>
      <xsl:if test="$format = $epub3">
        <xsl:attribute name="epub:type">footnotes</xsl:attribute>
      </xsl:if>
      <xsl:apply-templates/>
    </xsl:element>
  </xsl:template>
  <!-- Note, ref link already in text -->
  <xsl:template match="tei:ref[@type='note']">
    <xsl:param name="from"/>
    <xsl:call-template name="noteref">
      <xsl:with-param name="from" select="$from"/>
    </xsl:call-template>
  </xsl:template>
  <!-- Note, ref link in flow -->
  <xsl:template name="noteref">
    <xsl:param name="from"/>
    <xsl:param name="class">noteref</xsl:param>
    <xsl:variable name="id">
      <xsl:call-template name="id"/>
    </xsl:variable>
    <xsl:choose>
      <!-- In that case, links are already provided in the source -->
      <xsl:when test="parent::tei:seg[contains(@rendition, '#interval')]">
        <a id="{$id}_">&#x200c;</a>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="n">
          <xsl:call-template name="note-n">
            <xsl:with-param name="from" select="$from"/>
          </xsl:call-template>
        </xsl:variable>
        <!-- TODO, multi cibles -->
        <xsl:variable name="target">
          <xsl:choose>
            <xsl:when test="@target">
              <xsl:value-of select="substring-before(concat(@target, ' '), ' ')"/>
            </xsl:when>
            <xsl:otherwise>
              <xsl:call-template name="href">
                <xsl:with-param name="class" select="$class"/>
              </xsl:call-template>
            </xsl:otherwise>
          </xsl:choose>
        </xsl:variable>
        <!-- FBRreader -->
        <a class="{$class}" href="{$target}" id="{$id}_">
          <xsl:if test="$format = $epub3">
            <xsl:attribute name="epub:type">noteref</xsl:attribute>
          </xsl:if>
          <xsl:value-of select="$n"/>
        </a>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Display note number -->
  <xsl:template name="note-n">
    <xsl:variable name="resp" select="@resp"/>
    <xsl:variable name="hasformat" select="@rend != '' and boolean(translate(substring-before(concat(@rend, ' '), ' '), '1AaIi', '') = '')"/>
    <xsl:choose>
      <xsl:when test="@n">
        <xsl:value-of select="@n"/>
      </xsl:when>
      <!-- Renvoi à une note -->
      <xsl:when test="self::tei:ref[@type='note']">
        <xsl:for-each select="key('id', substring-after(@target, '#'))[1]">
          <xsl:call-template name="n"/>
        </xsl:for-each>
      </xsl:when>
      <!--
      <xsl:when test="self::tei:app">
        <xsl:number count="tei:app" format="a" level="any" from="*[key('split', generate-id())]"/>
      </xsl:when>
      note number by book, not by chapter
      <xsl:when test="$hasformat and ancestor::*[key('split', generate-id())] and $fnpage = ''">
        <xsl:number count="tei:note[@rend=$hasformat]" level="any" from="*[key('split', generate-id())]" format="{@rend}"/>
      </xsl:when>
      -->
      <xsl:when test="$hasformat">
        <xsl:number count="tei:note[@rend=$hasformat]" format="{@rend}" from="tei:text" level="any"/>
      </xsl:when>
      <xsl:when test="@type='app'">
        <xsl:number count="tei:note[@type='app']" format="I" from="tei:text" level="any"/>
      </xsl:when>
      <xsl:when test="@resp='editor'">
        <xsl:number count="tei:note[@resp=$resp]" format="a" from="tei:text" level="any"/>
      </xsl:when>
      <xsl:when test="@resp">
        <xsl:number count="tei:note[@resp=$resp]" from="tei:text" level="any"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:number count="tei:note[not(@resp) and not(@rend) and not(@place='margin') and not(parent::tei:div) and not(parent::tei:notesStmt)]" from="tei:text" level="any"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Note, link to return to anchor -->
  <xsl:template name="noteback">
    <xsl:param name="from"/>
    <xsl:param name="class">noteback</xsl:param>
    <xsl:variable name="id">
      <xsl:call-template name="id"/>
    </xsl:variable>
    <a class="{$class}">
      <xsl:attribute name="href">
        <xsl:choose>
          <xsl:when test="@target">
            <xsl:value-of select="substring-before(concat(@target, ' '), ' ')"/>
          </xsl:when>
          <xsl:otherwise>
            <!-- Call a centralized note href template, for epub deported notes -->
            <xsl:call-template name="href">
              <xsl:with-param name="class">
                <xsl:value-of select="$class"/>
              </xsl:with-param>
            </xsl:call-template>
            <xsl:text>_</xsl:text>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:attribute>
      <xsl:call-template name="note-n">
        <xsl:with-param name="from" select="$from"/>
      </xsl:call-template>
      <xsl:if test="$class = 'noteback'">
        <xsl:text>. </xsl:text>
      </xsl:if>
    </a>
  </xsl:template>
  <!--Default behavior for note-->
  <xsl:template match="tei:note | tei:*[@rend='note']">
    <xsl:param name="from"/>
    <xsl:choose>
      <xsl:when test="@place = 'margin'">
        <xsl:call-template name="note">
          <xsl:with-param name="from" select="$from"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="noteref">
          <xsl:with-param name="from" select="$from"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Display note text with a link back to flow -->
  <xsl:template name="note">
    <xsl:param name="from"/>
    <!-- identifiant de la note -->
    <xsl:variable name="id">
      <xsl:call-template name="id"/>
    </xsl:variable>
    <xsl:variable name="text">
      <xsl:for-each select="text()">
        <xsl:value-of select="normalize-space(.)"/>
      </xsl:for-each>
    </xsl:variable>
    <aside>
      <xsl:call-template name="noteatts">
        <xsl:with-param name="class">
          <xsl:choose>
            <xsl:when test="@resp='author'">1</xsl:when>
            <xsl:when test="@resp='editor'">a</xsl:when>
          </xsl:choose>
        </xsl:with-param>
      </xsl:call-template>
      <xsl:attribute name="id">
        <xsl:value-of select="$id"/>
      </xsl:attribute>
      <xsl:attribute name="role">
        <xsl:text>note</xsl:text>
      </xsl:attribute>
      <xsl:if test="$format = $epub3">
        <xsl:attribute name="epub:type">note</xsl:attribute>
      </xsl:if>
      <xsl:variable name="noteback">
        <xsl:choose>
          <xsl:when test="@place = 'margin'"/>
          <xsl:otherwise>
            <xsl:call-template name="noteback">
              <xsl:with-param name="from" select="$from"/>
            </xsl:call-template>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:variable>
      <xsl:choose>
        <xsl:when test="$text='' and count(*)=1 and (tei:p or tei:quote)">
          <xsl:copy-of select="$noteback"/>
          <xsl:apply-templates select="*/node()"/>
        </xsl:when>
        <xsl:when test="@place = 'margin' and $text=''">
          <xsl:for-each select="*">
            <xsl:apply-templates/>
            <xsl:if test="position()!=last()">
              <br/>
            </xsl:if>
          </xsl:for-each>
        </xsl:when>
        <xsl:when test="$text='' and *[1][self::tei:p]">
          <p class="noindent">
            <xsl:copy-of select="$noteback"/>
            <xsl:apply-templates select="*[1]/node()"/>
          </p>
          <xsl:apply-templates select="*[position() &gt; 1]"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:copy-of select="$noteback"/>
          <xsl:apply-templates/>
        </xsl:otherwise>
      </xsl:choose>
    </aside>
    <!-- TOTEST
      <xsl:choose>
        <xsl:when test="@ana">
          <xsl:text> </xsl:text>
          <i>
            <xsl:value-of select="@ana"/>
          </i>
          <xsl:text>. </xsl:text>
        </xsl:when>
      </xsl:choose>
      -->
  </xsl:template>
  <!-- Mode app -->
  <xsl:template match="node()" mode="app" name="app">
    <xsl:choose>
      <xsl:when test="self::tei:app[@rend='table']"/>
      <!-- Variante, en note de bas de page -->
      <xsl:when test="self::tei:app">
        <span>
          <xsl:call-template name="noteatts"/>
          <xsl:text> </xsl:text>
          <xsl:apply-templates select="tei:rdg | tei:note | tei:witDetail"/>
        </span>
      </xsl:when>
      <!-- Note normalisée pour ajout -->
      <xsl:when test="self::tei:supplied">
        <!-- Pas besoin de rappel pour note en contexte
        <xsl:choose>
          <xsl:when test="local-name(..)='w'">
            <xsl:apply-templates select=".." mode="title"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:apply-templates mode="title"/>
          </xsl:otherwise>
        </xsl:choose>
        -->
        <xsl:if test="@source">
          <xsl:text> </xsl:text>
          <i>
            <xsl:call-template name="anchors">
              <xsl:with-param name="anchors" select="@source"/>
            </xsl:call-template>
          </i>
        </xsl:if>
        <xsl:if test="@reason">
          <xsl:text> </xsl:text>
          <i>
            <xsl:call-template name="message">
              <xsl:with-param name="id" select="@reason"/>
            </xsl:call-template>
          </i>
        </xsl:if>
        <xsl:choose>
          <xsl:when test="local-name(..)='w'">
            <xsl:text> [ </xsl:text>
            <xsl:apply-templates/>
          </xsl:when>
        </xsl:choose>
      </xsl:when>
      <!-- Note normalisée pour correction -->
      <xsl:when test="self::tei:choice">
        <!-- pas de rappel de la forme corrigée
        <xsl:apply-templates select="tei:corr/node() | tei:expan/node() | tei:reg/node()"/>
        <xsl:text> </xsl:text>
        -->
        <xsl:choose>
          <xsl:when test="tei:corr/@source">
            <i>
              <xsl:call-template name="anchors">
                <xsl:with-param name="anchors" select="tei:corr/@source"/>
              </xsl:call-template>
            </i>
            <xsl:text> [ </xsl:text>
          </xsl:when>
          <xsl:otherwise> [ </xsl:otherwise>
        </xsl:choose>
        <xsl:apply-templates select="tei:sic/node() | tei:abbr/node() | tei:orig/node()"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  <!-- Le template principal affichant des notes hors flux -->
  <xsl:template match="tei:note" mode="fn" name="fn">
    <xsl:param name="resp"/>
    <xsl:choose>
      <!-- not a note, go in  -->
      <xsl:when test="not(self::tei:note)">
        <xsl:apply-templates mode="fn" select="*">
          <xsl:with-param name="resp" select="$resp"/>
        </xsl:apply-templates>
      </xsl:when>
      <!-- do not output block notes -->
      <xsl:when test="parent::tei:app or parent::tei:back or parent::tei:div or parent::tei:div1 or parent::tei:div2 or parent::tei:div3 or parent::tei:front or parent::tei:notesStmt or parent::tei:sp"/>
      <xsl:when test="$resp= '' and not(@resp)">
        <xsl:call-template name="note"/>
      </xsl:when>
      <xsl:when test="@resp and @resp=$resp">
        <xsl:call-template name="note"/>
      </xsl:when>
      <!-- note other than author or editor -->
      <xsl:when test="$resp ='' and @resp and @resp != 'author'  and @resp != 'editor'">
        <xsl:call-template name="note"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  <!-- Citations numérotées, la référence apparaît en note de bas de page, une animation permet de la mettre en valeur -->
  <xsl:template match="tei:p//tei:cit[@n]">
    <span>
      <xsl:call-template name="noteatts">
        <xsl:with-param name="class">cit_n</xsl:with-param>
      </xsl:call-template>
      <xsl:for-each select="*">
        <xsl:choose>
          <xsl:when test="self::tei:bibl"/>
          <xsl:otherwise>
            <xsl:text> </xsl:text>
            <xsl:apply-templates select="."/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
      <xsl:variable name="id">
        <xsl:call-template name="id"/>
      </xsl:variable>
      <!-- FBRreader -->
      <sup>
        <a class="noteref" href="#{$id}" name="_{$id}">
          <xsl:if test="$format = $epub3">
            <xsl:attribute name="epub:type">noteref</xsl:attribute>
          </xsl:if>
          <!-- xsl:attribute name="onclick">if(this.cloc) {this.parentNode.className='cit_n'; this.cloc=null; } else { this.cloc=true;  this.parentNode.className='cit_n_bibl'}; return true;</xsl:attribute -->
          <xsl:attribute name="onmouseover">this.parentNode.className='cit_n_bibl'</xsl:attribute>
          <xsl:attribute name="onmouseout">this.parentNode.className='cit_n'</xsl:attribute>
          <xsl:value-of select="@n"/>
        </a>
      </sup>
      <span class="listBibl">
        <xsl:apply-templates select="tei:bibl"/>
      </span>
    </span>
  </xsl:template>
  <!-- Note sans appel -->
  <xsl:template match="tei:back/tei:note | tei:div/tei:note | tei:div0/tei:note | tei:div1/tei:note | tei:div2/tei:note | tei:div3/tei:note | tei:div4/tei:note | tei:front/tei:note | tei:sp/tei:note">
    <xsl:choose>
      <xsl:when test="not(tei:p|tei:div)">
        <p>
          <xsl:call-template name="noteatts"/>
          <xsl:apply-templates/>
        </p>
      </xsl:when>
      <xsl:otherwise>
        <div>
          <xsl:call-template name="noteatts"/>
          <xsl:apply-templates/>
        </div>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Note, texte, pour les notes déportée -->
  <xsl:template match="tei:div[@type='notes']/tei:note" priority="5">
    <xsl:call-template name="note"/>
  </xsl:template>
  <!--
<h3>Apparat critique</h3>
  -->
  <!-- Variante normale, traitement dynamique -->
  <xsl:template match="tei:app">
    <xsl:param name="from"/>
    <xsl:variable name="el">
      <xsl:choose>
        <xsl:when test="tei:p">div</xsl:when>
        <xsl:otherwise>span</xsl:otherwise>
      </xsl:choose>
    </xsl:variable>
    <xsl:element name="{$el}" namespace="http://www.w3.org/1999/xhtml">
      <xsl:attribute name="id">
        <xsl:call-template name="id">
          <xsl:with-param name="suffix">_</xsl:with-param>
        </xsl:call-template>
      </xsl:attribute>
      <xsl:attribute name="class">app</xsl:attribute>
      <xsl:attribute name="onmouseover">this.className='apprdg'</xsl:attribute>
      <xsl:attribute name="onmouseout">if(!this.cloc) this.className='app'</xsl:attribute>
      <xsl:attribute name="onclick">if(this.cloc) {this.className='app'; this.cloc=null; } else { this.cloc=true; this.className='apprdg'}; return true;</xsl:attribute>
      <!-- ajouts et omissions -->
      <xsl:apply-templates select="tei:lem"/>
      <xsl:call-template name="noteref">
        <xsl:with-param name="from" select="$from"/>
      </xsl:call-template>
      <span class="rdgList">
        <xsl:text> </xsl:text>
        <xsl:apply-templates select="tei:rdg | tei:witDetail | tei:note"/>
        <xsl:text> </xsl:text>
      </span>
    </xsl:element>
  </xsl:template>
  <!-- version de base de la variante -->
  <xsl:template match="tei:lem">
    <xsl:variable name="prev" select="normalize-space(../preceding-sibling::node()[1])"/>
    <!-- généralement, un espace avant, sauf si apostrophe -->
    <xsl:if test="translate (substring($prev, string-length($prev)), concat($apos, '’'), '') != ''">
      <xsl:text> </xsl:text>
    </xsl:if>
    <span>
      <xsl:call-template name="noteatts"/>
      <xsl:apply-templates/>
    </span>
  </xsl:template>
  <!-- Autre leçon -->
  <xsl:template match="tei:rdg | tei:witDetail">
    <xsl:choose>
      <!-- même leçon que le précédent -->
      <xsl:when test=" . = preceding-sibling::tei:rdg">, </xsl:when>
      <xsl:when test="preceding-sibling::tei:rdg"> ; </xsl:when>
    </xsl:choose>
    <span>
      <xsl:call-template name="noteatts"/>
      <!-- si même leçon que la précédente, pas la pein de la copier -->
      <xsl:choose>
        <xsl:when test=". = preceding-sibling::tei:rdg"/>
        <xsl:otherwise>
          <xsl:apply-templates/>
        </xsl:otherwise>
      </xsl:choose>
      <xsl:call-template name="anchors"/>
      <xsl:choose>
        <!-- Mot clé, chercher un intitulé -->
        <xsl:when test="@cause and normalize-space(@cause) != ''">
          <xsl:text> </xsl:text>
          <i>
            <xsl:call-template name="message">
              <xsl:with-param name="id" select="@cause"/>
            </xsl:call-template>
          </i>
        </xsl:when>
        <!-- texte libre -->
        <xsl:when test="@hand|@place and normalize-space(@hand|@place) != ''">
          <xsl:text> </xsl:text>
          <i>
            <xsl:value-of select="@hand|@place"/>
          </i>
        </xsl:when>
        <!-- suivant identique, lui laisser l'intitulé automatique -->
        <xsl:when test=" . = following-sibling::tei:rdg"/>
        <!-- Rien en principal, c'est une addition -->
        <xsl:when test="../lem = ''">
          <xsl:text> </xsl:text>
          <i>
            <xsl:call-template name="message">
              <xsl:with-param name="id">add</xsl:with-param>
            </xsl:call-template>
          </i>
        </xsl:when>
        <!-- Rien à ajouter, c'est une omission -->
        <xsl:when test=". = ''">
          <xsl:text> </xsl:text>
          <i>
            <xsl:call-template name="message">
              <xsl:with-param name="id">omit</xsl:with-param>
            </xsl:call-template>
          </i>
        </xsl:when>
      </xsl:choose>
    </span>
  </xsl:template>
  <!-- abbréviation, avec possible forme normale -->
  <xsl:template match="tei:abbr">
    <abbr>
      <xsl:call-template name="noteatts"/>
      <xsl:apply-templates/>
    </abbr>
  </xsl:template>
  <!-- expansion signalée en cours de texte -->
  <xsl:template match="tei:expan">
    <xsl:text>[</xsl:text>
    <xsl:apply-templates/>
    <xsl:text>]</xsl:text>
  </xsl:template>
  <!-- développement silencieux d’abréviation -->
  <xsl:template match="tei:ex">
    <ins>
      <xsl:call-template name="noteatts"/>
      <xsl:apply-templates/>
    </ins>
  </xsl:template>
  <!-- Texte ajouté -->
  <xsl:template match="tei:choice/tei:supplied">
    <ins>
      <xsl:choose>
        <xsl:when test="@source">
          <xsl:call-template name="noteatts">
            <xsl:with-param name="class">tipshow</xsl:with-param>
          </xsl:call-template>
          <!-- ??
          <small class="tip">
            <xsl:call-template name="fn"/>
          </small>
          -->
        </xsl:when>
        <xsl:otherwise>
          <xsl:call-template name="noteatts"/>
        </xsl:otherwise>
      </xsl:choose>
      <xsl:text>[</xsl:text>
      <xsl:apply-templates/>
      <xsl:text>]</xsl:text>
    </ins>
  </xsl:template>
  <xsl:template match="tei:supplied">
    <xsl:choose>
      <xsl:when test="not(../tei:w)"/>
      <xsl:when test="preceding-sibling::*[1][self::tei:w]">
        <xsl:text> </xsl:text>
      </xsl:when>
    </xsl:choose>
    <xsl:text>[</xsl:text>
    <xsl:apply-templates/>
    <xsl:text>]</xsl:text>
  </xsl:template>
  <!-- Specific BFM -->
  <xsl:template match="tei:surplus">
    <xsl:text>(</xsl:text>
    <xsl:apply-templates/>
    <xsl:text>)</xsl:text>
  </xsl:template>
  <!-- @source pour attribut title -->
  <xsl:template match="@source" mode="title">
    <xsl:text> : </xsl:text>
    <xsl:call-template name="anchors">
      <xsl:with-param name="anchors" select="."/>
    </xsl:call-template>
  </xsl:template>
  <!-- @reason pour attribut title -->
  <xsl:template match="@reason" mode="title">
    <xsl:text>, </xsl:text>
    <xsl:value-of select="."/>
  </xsl:template>
  <!-- Insertions -->
  <xsl:template match="tei:add">
    <ins>
      <xsl:call-template name="noteatts"/>
      <xsl:apply-templates/>
    </ins>
  </xsl:template>
  <!-- Suppressions -->
  <xsl:template match="tei:del">
    <del>
      <xsl:call-template name="noteatts"/>
      <xsl:apply-templates/>
      <xsl:if test="@ana">
        <xsl:text> [</xsl:text>
        <xsl:call-template name="message">
          <xsl:with-param name="id" select="@ana"/>
        </xsl:call-template>
        <xsl:text>]</xsl:text>
      </xsl:if>
    </del>
  </xsl:template>
  <!-- Alternative, conserver les espacements (faut-il un conteneur ?) -->
  <xsl:template match="tei:choice|tei:subst">
    <xsl:choose>
      <xsl:when test="false()">
        <span>
          <xsl:call-template name="noteatts">
            <xsl:with-param name="class">tipshow</xsl:with-param>
          </xsl:call-template>
          <!-- ??
          <small class="tip">
            <xsl:call-template name="fn"/>
          </small>
          -->
          <xsl:apply-templates select="*"/>
        </span>
      </xsl:when>
      <xsl:when test="tei:orig and tei:reg">
        <span>
          <xsl:call-template name="noteatts"/>
          <xsl:attribute name="title">
            <xsl:if test="*/@source">
              <xsl:value-of select="*/@source"/>
              <xsl:text> : </xsl:text>
            </xsl:if>
            <xsl:apply-templates mode="title" select="tei:orig"/>
          </xsl:attribute>
          <xsl:apply-templates select="tei:reg/node()"/>
        </span>
      </xsl:when>
      <xsl:when test="tei:sic and tei:corr">
        <span>
          <xsl:call-template name="noteatts"/>
          <xsl:attribute name="title">
            <xsl:if test="*/@source">
              <xsl:value-of select="*/@source"/>
              <xsl:text> : </xsl:text>
            </xsl:if>
            <xsl:apply-templates mode="title" select="tei:sic"/>
          </xsl:attribute>
          <xsl:apply-templates select="tei:corr/node()"/>
        </span>
      </xsl:when>
      <xsl:when test="tei:add and tei:del">
        <span>
          <xsl:call-template name="noteatts"/>
          <xsl:attribute name="title">
            <xsl:if test="*/@source">
              <xsl:value-of select="*/@source"/>
              <xsl:text> : </xsl:text>
            </xsl:if>
            <xsl:apply-templates mode="title" select="tei:del"/>
          </xsl:attribute>
          <xsl:apply-templates select="tei:add/node()"/>
        </span>
      </xsl:when>
      <xsl:when test="tei:abbr and tei:expan">
        <span>
          <xsl:call-template name="noteatts"/>
          <xsl:attribute name="title">
            <xsl:if test="*/@source">
              <xsl:value-of select="*/@source"/>
              <xsl:text> : </xsl:text>
            </xsl:if>
            <xsl:apply-templates mode="title" select="tei:abbr"/>
          </xsl:attribute>
          <xsl:apply-templates select="tei:expan/node()"/>
        </span>
      </xsl:when>
      <xsl:otherwise>
        <span>
          <xsl:call-template name="noteatts"/>
          <xsl:apply-templates select="*"/>
        </span>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  <!-- Correction -->
  <xsl:template match="tei:choice/tei:corr | tei:reg | tei:choice[tei:abbr]/tei:expan">
    <ins>
      <xsl:call-template name="noteatts"/>
      <xsl:if test="preceding-sibling::*">
        <xsl:text> </xsl:text>
      </xsl:if>
      <xsl:apply-templates/>
    </ins>
  </xsl:template>
  <xsl:template match="tei:choice/tei:sic | tei:choice/tei:abbr | tei:orig">
    <del>
      <xsl:call-template name="noteatts"/>
      <!-- Espace avant un mot ? attention si l'alternative est au milieu d'un mot -->
      <xsl:apply-templates/>
    </del>
  </xsl:template>
  <!-- (?) -->
  <xsl:template match="tei:interp">
    <span>
      <xsl:call-template name="noteatts"/>
      <xsl:apply-templates/>
    </span>
  </xsl:template>
  <xsl:template match="tei:unclear">
    <xsl:apply-templates/>
    <xsl:text> [?] </xsl:text>
  </xsl:template>
  <xsl:template match="tei:gap">
    <span class="gap">
      <xsl:attribute name="title">
        <xsl:call-template name="message">
          <xsl:with-param name="id">gap</xsl:with-param>
        </xsl:call-template>
        <xsl:if test="@extent">
          <xsl:text> : </xsl:text>
          <xsl:value-of select="@extent"/>
          <xsl:text> </xsl:text>
          <xsl:call-template name="message">
            <xsl:with-param name="id">chars</xsl:with-param>
          </xsl:call-template>
        </xsl:if>
      </xsl:attribute>
      <xsl:choose>
        <xsl:when test="not(../tei:w)"/>
        <xsl:when test="preceding-sibling::*[1][self::tei:w]">
          <xsl:text> </xsl:text>
        </xsl:when>
      </xsl:choose>
      <xsl:text>[</xsl:text>
      <xsl:choose>
        <xsl:when test="@extent and not(text())">
          <xsl:value-of select="substring('........................................................................................................', 1, @extent)"/>
        </xsl:when>
        <xsl:when test="@reason">
          <xsl:call-template name="message">
            <xsl:with-param name="id" select="@reason"/>
          </xsl:call-template>
          <xsl:if test="@unit">
            <xsl:text> </xsl:text>
            <xsl:value-of select="@unit"/>
          </xsl:if>
        </xsl:when>
        <xsl:when test=". != ''">
          <xsl:apply-templates/>
        </xsl:when>
        <xsl:otherwise>…</xsl:otherwise>
      </xsl:choose>
      <xsl:text>]</xsl:text>
    </span>
  </xsl:template>
  <!-- Commentaire libre dans une note d'apparat critique. -->
  <xsl:template match="tei:app/tei:note">
    <xsl:apply-templates/>
  </xsl:template>
  <!-- Dommage -->
  <xsl:template match="tei:damage">
    <span>
      <xsl:call-template name="noteatts"/>
      <xsl:text>[</xsl:text>
      <xsl:apply-templates/>
      <xsl:text>]</xsl:text>
    </span>
  </xsl:template>
  <!-- Conteneur de variantes importantes -->
  <xsl:template match="tei:app[@rend='table']">
    <table>
      <xsl:call-template name="noteatts"/>
      <!-- afficher l'intitulé de witness -->
      <xsl:for-each select="*[. != '']">
        <xsl:variable name="pos">
          <xsl:number/>
        </xsl:variable>
        <th width="{floor(100 div count(../tei:rdg|../tei:lem))} %">
          <xsl:call-template name="anchors">
            <xsl:with-param name="att">wit</xsl:with-param>
          </xsl:call-template>
        </th>
      </xsl:for-each>
      <tr>
        <xsl:for-each select="*[. != '']">
          <td valign="top">
            <xsl:choose>
              <xsl:when test="position()=1">
                <xsl:call-template name="noteatts">
                  <xsl:with-param name="class">first</xsl:with-param>
                </xsl:call-template>
              </xsl:when>
              <xsl:otherwise>
                <xsl:call-template name="noteatts">
                  <xsl:with-param name="class">more</xsl:with-param>
                </xsl:call-template>
              </xsl:otherwise>
            </xsl:choose>
            <xsl:apply-templates/>
          </td>
        </xsl:for-each>
      </tr>
    </table>
  </xsl:template>
  <xsl:template name="noteatts">
    <!-- Add some html classes to the automatic ones -->
    <xsl:param name="class"/>
    <!-- Ddelegate class attribution to another template -->
    <xsl:call-template name="noteclass">
      <xsl:with-param name="class" select="$class"/>
    </xsl:call-template>
    <!-- Shall we identify element ? -->
    <xsl:choose>
      <xsl:when test="normalize-space(@xml:id) != ''">
        <xsl:attribute name="id">
          <xsl:value-of select="translate(normalize-space(@xml:id), ' ', '')"/>
        </xsl:attribute>
      </xsl:when>
    </xsl:choose>
    <!-- Process other know attributes -->
    <xsl:apply-templates select="@*"/>
  </xsl:template>
  <!-- Provide automatic classes from TEI names -->
  <xsl:template name="noteclass">
    <xsl:param name="class"/>
    <!-- @rend may be used as a free text attribute, be careful -->
    <xsl:variable name="value">
      <xsl:value-of select="$class"/>
      <xsl:text> </xsl:text>
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
      <xsl:text>note</xsl:text>
      <xsl:text> </xsl:text>
      <!-- lang is a useful class for some rendering (ex: greek fonts) -->
      <xsl:value-of select="@xml:lang"/>
      <xsl:text> </xsl:text>
      <!-- parameter value -->
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
</xsl:transform>
