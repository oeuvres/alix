<?xml version="1.0" encoding="UTF-8"?>
<xsl:transform version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns="http://www.w3.org/1999/xhtml"
  xmlns:alix="http://alix.casa"
>
  <xsl:template match="/">
    <alix:document>
      <xsl:attribute name="xml:id">test</xsl:attribute>
      <alix:field name="text" type="text">Ça marche</alix:field>
    </alix:document>
  </xsl:template>
</xsl:transform>