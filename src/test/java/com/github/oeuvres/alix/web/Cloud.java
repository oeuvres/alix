/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.oeuvres.alix.web;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.security.InvalidParameterException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Word Cloud in HTML from Pierre Lindenbaum
 * https://github.com/lindenb/jsandbox/blob/master/src/sandbox/MyWordle.java
 * Rewrited with no deps. Works, but is not nice.
 */

public class Cloud
{
    /** List of words to render */
    private List<Word> words = new ArrayList<Word>();
    /** Default font for size calculations */
    private String font = "Dialog";
    /** minimum font size */
    private int fontmin = 12;
    /** maximum font size */
    private int fontmax = 80;
    /** Default font Color in PNG export */
    private Color fill = Color.GRAY;
    /** Default font strokes in PNG export */
    private Color stroke = null;
    /** A locale for decimal formats */
    static DecimalFormatSymbols dfus = DecimalFormatSymbols.getInstance(Locale.US);
    /** Decimal format for CSS values */
    static DecimalFormat dfdec1 = new DecimalFormat("#.0", dfus);
    /** Radius steps to go outside */
    private double dRadius = 2.0;
    /**  */
    private double ratio = 1.3;
    /** spirograph angle for tests */
    private int dDeg = 10;
    /** Random series */

    private Random rand = new Random();
    private Rectangle2D imageSize = null;
    private boolean useArea = false;

    private Integer outputWidth = null;

    public class CSS
    {

    }

    /**
     * A word in the cloud, with all its fields.
     */
    static public class Word
    {
        /** Required, a word to display */
        public final String label;
        /** Required, a relative weight */
        public final double weight;
        /** Optional, a wordclass for rendering properties of this word */
        public final Wordclass wclass;
        /** The full path of the TTF word */
        private Shape shape;
        /**
         * A bounding rectangle, not optimal
         * https://www.jasondavies.com/wordcloud/about/
         */
        private Rectangle2D bounds;
        /** Fontsize calculated, between fontmin and fontmax */
        private int fontsize;

        public Word(final String label, final double weight, Wordclass wclass) {
            this.label = label;
            this.weight = weight;
            if (this.weight <= 0)
                throw new IllegalArgumentException("bad weight " + weight);
            this.wclass = wclass;
        }

        @Override
        public String toString()
        {
            return label + " (" + weight + ")";
        }

    }

    /**
     * A class for a word with colors font etc.
     */
    static public class Wordclass
    {
        private final String name;
        private final String font;
        private final Color fill;
        private final Color stroke;

        public Wordclass(String name, String font, Color fill, Color stroke) {
            this.name = name;
            this.font = font;
            this.fill = fill;
            this.stroke = stroke;
        }
    }

    public Cloud() {
    }

    /**
     * Add a word to the cloud
     * 
     * @param word
     */
    public void add(Word word)
    {
        this.words.add(word);
    }

    /**
     * Here is the heart
     * 
     * @throws InvalidParameterException If no words.
     */
    public void doLayout() throws InvalidParameterException
    {
        if (this.words.isEmpty())
            throw new InvalidParameterException("No words to display");
        // this.shuffle(); // display in input order
        // ? size ?
        this.imageSize = new Rectangle2D.Double(0, 0, 0, 0);

        // get minimum and maximum weight
        Word first = this.words.get(0);
        double high = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        for (Word w : this.words) {
            high = Math.max(high, w.weight);
            low = Math.min(low, w.weight);
        }

        // create small image (?)
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        // get graphics from this image
        Graphics2D g = Graphics2D.class.cast(img.getGraphics());
        FontRenderContext frc = g.getFontRenderContext();

        // loop on words to create the shapes and bounding rectangle. Is it the right
        // place for rotations ?
        for (Word w : this.words) {
            String font = this.font;
            if (w.wclass != null && w.wclass.font != null)
                font = w.wclass.font;

            double rate = (w.weight - low) / (high - low);
            int fontsize = (int) ((this.fontmax - this.fontmin) * rate) + this.fontmin;
            w.fontsize = fontsize;
            // TODO Bold ? Italic ? padding with spaces ?
            TextLayout textLayout = new TextLayout(w.label, new Font(font, 0, fontsize), frc);
            Shape shape = textLayout.getOutline(null);
            /*
             * if (this.allowRotate && this.rand.nextBoolean()) { AffineTransform rotate =
             * AffineTransform.getRotateInstance( Math.PI / 2.0 ); shape =
             * rotate.createTransformedShape( shape ); }
             */
            Rectangle2D bounds = shape.getBounds2D();
            AffineTransform centerTr = AffineTransform.getTranslateInstance(-bounds.getCenterX(), -bounds.getCenterY());
            w.shape = centerTr.createTransformedShape(shape);
            w.bounds = w.shape.getBounds2D();
        }
        g.dispose();

        // first point
        Point2D.Double center = new Point2D.Double(200, 200);

        // loop on words
        for (int i = 1; i < this.words.size(); ++i) {
            Word current = this.words.get(i);

            // find a center for this word
            center.x = 0;
            center.y = 0;
            double totalWeight = 0.0;
            for (int prev = 0; prev < i; ++prev) {
                Word wPrev = this.words.get(prev);
                center.x += (wPrev.bounds.getCenterX()) * wPrev.weight;
                center.y += (wPrev.bounds.getCenterY()) * wPrev.weight;
                totalWeight += wPrev.weight;
            }
            center.x /= (totalWeight);
            center.y /= (totalWeight);

            // TODO
            // Shape shaveH = current.shape;
            // Rectangle2D bounds = current.bounds;

            boolean done = false;
            double radx = ratio * Math.min(first.bounds.getWidth(), first.bounds.getHeight());
            double rady = ratio * Math.min(first.bounds.getWidth(), first.bounds.getHeight());

            while (!done) {
                // System.err.println( "" + i + "/" + words.size() + " rad:" + radius );
                int startDeg = rand.nextInt(360);
                // loop over spiral
                int prev_x = -1;
                int prev_y = -1;
                for (int deg = startDeg; deg < startDeg + 360; deg += dDeg) {
                    // double sq = Math.abs(deg % 90 - 45) / 45.0;
                    double rad = (deg / Math.PI) * 180.0;
                    // 1+0.5*sq *
                    int cx = (int) (center.x + radx * Math.cos(rad));
                    int cy = (int) (center.y + rady * Math.sin(rad));
                    if (prev_x == cx && prev_y == cy)
                        continue;
                    prev_x = cx;
                    prev_y = cy;

                    AffineTransform moveTo = AffineTransform.getTranslateInstance(cx, cy);
                    Shape candidate = moveTo.createTransformedShape(current.shape);
                    Area area1 = null;
                    Rectangle2D bound1 = null;
                    if (useArea) {
                        area1 = new Area(candidate);
                    } else {
                        bound1 = new Rectangle2D.Double(current.bounds.getX() + cx, current.bounds.getY() + cy,
                                current.bounds.getWidth(), current.bounds.getHeight());
                    }
                    // any collision ?
                    int prev = 0;
                    for (prev = 0; prev < i; ++prev) {
                        if (useArea) {
                            Area area2 = new Area(this.words.get(prev).shape);
                            area2.intersect(area1);
                            if (!area2.isEmpty())
                                break;
                        } else {
                            if (bound1.intersects(this.words.get(prev).bounds)) {
                                break;
                            }
                        }
                    }
                    // no collision: we're done
                    if (prev == i) {
                        current.shape = candidate;
                        current.bounds = candidate.getBounds2D();
                        done = true;
                        break;
                    }
                }
                radx += this.dRadius * ratio;
                rady += this.dRadius / ratio;
            }
        }

        double minx = Integer.MAX_VALUE;
        double miny = Integer.MAX_VALUE;
        double maxx = -Integer.MAX_VALUE;
        double maxy = -Integer.MAX_VALUE;
        for (Word w : words) {
            minx = Math.min(minx, w.bounds.getMinX() + 1);
            miny = Math.min(miny, w.bounds.getMinY() + 1);
            maxx = Math.max(maxx, w.bounds.getMaxX() + 1);
            maxy = Math.max(maxy, w.bounds.getMaxY() + 1);
        }
        AffineTransform shiftTr = AffineTransform.getTranslateInstance(-minx, -miny);
        for (Word w : words) {
            w.shape = shiftTr.createTransformedShape(w.shape);
            w.bounds = w.shape.getBounds2D();
        }
        this.imageSize = new Rectangle2D.Double(0, 0, maxx - minx, maxy - miny);
    }

    /**
     * Sorting words by weight in ascendant order
     */
    public void sortweight()
    {
        Collections.sort(this.words, new Comparator<Word>() {
            @Override
            public int compare(Word w1, Word w2)
            {
                return Double.compare(w1.weight, w2.weight);
            }
        });

    }

    /**
     * Reverse sort of words by weight, descendant order
     */
    public void rsortweight()
    {
        Collections.sort(this.words, new Comparator<Word>() {
            @Override
            public int compare(Word w1, Word w2)
            {
                return Double.compare(w2.weight, w1.weight);
            }
        });
    }

    /**
     * Alphabetic sort of words
     */
    public void sortalpha()
    {
        Collections.sort(this.words, new Comparator<Word>() {
            @Override
            public int compare(Word w1, Word w2)
            {
                return w1.label.compareTo(w2.label);
            }
        });

    }

    /**
     * Shuffle words
     */
    public void shuffle()
    {
        Collections.shuffle(this.words, this.rand);
    }

    /**
     * Output a PNG file
     * 
     * @param file
     * @throws IOException Lucene errors.
     */
    public void png(File file) throws IOException
    {
        AffineTransform scale = new AffineTransform();
        Dimension dim = new Dimension((int) this.imageSize.getWidth(), (int) this.imageSize.getHeight());

        if (this.outputWidth != null) {
            double ratio = this.outputWidth / dim.getWidth();
            dim.width = this.outputWidth;
            dim.height = (int) (dim.getHeight() * ratio);
            scale = AffineTransform.getScaleInstance(ratio, ratio);
        }

        BufferedImage img = new BufferedImage(dim.width, dim.height, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = (Graphics2D) img.getGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setTransform(scale);
        for (Word w : this.words) {
            Color c = this.fill;
            if (w.wclass != null && w.wclass.fill != null)
                c = w.wclass.fill;
            if (c != null) {
                g.setColor(c);
                g.fill(w.shape);
            }
            c = this.stroke;
            if (w.wclass != null && w.wclass.stroke != null)
                c = w.wclass.stroke;
            if (c != null) {
                Stroke old = g.getStroke();
                // TODO, lineHeight ?
                g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.setColor(c);
                g.draw(w.shape);
                g.setStroke(old);
            }
        }

        g.dispose();
        ImageIO.write(img, "png", file);
    }

    public void html(File file, final String href) throws IOException, XMLStreamException
    {
        FileWriter writer = new FileWriter(file);
        html(writer, href);
        writer.close();
    }

    public String html(final String href) throws XMLStreamException, IOException
    {
        StringWriter writer = new StringWriter();
        html(writer, href);
        writer.close();
        return writer.toString();
    }

    public String html() throws XMLStreamException, IOException
    {
        StringWriter writer = new StringWriter();
        html(writer, null);
        writer.close();
        return writer.toString();
    }

    public void html(File file) throws XMLStreamException, IOException
    {
        FileWriter writer = new FileWriter(file);
        html(writer, null);
        writer.close();
    }

    /**
     * Output an html file
     * 
     * @param writer
     * @param href
     * @throws IOException        Lucene errors.
     * @throws XMLStreamException
     */
    public void html(Writer writer, final String href) throws IOException, XMLStreamException
    {
        final String HTML = "http://www.w3.org/1999/xhtml";
        XMLOutputFactory xmlfactory = XMLOutputFactory.newInstance();
        XMLStreamWriter xml = xmlfactory.createXMLStreamWriter(writer);
        xml.setPrefix("", HTML);
        xml.writeStartElement(HTML, "div");
        xml.writeAttribute("class", "wordcloud");

        double width = this.imageSize.getWidth();
        double height = this.imageSize.getHeight();

        for (Word w : this.words) {
            xml.writeCharacters("\n");
            xml.writeStartElement(HTML, "a");
            if (href != null)
                xml.writeAttribute("href", href + w.label);
            xml.writeAttribute("title", w.toString());
            if (w.wclass != null)
                xml.writeAttribute("class", w.wclass.name);
            xml.writeAttribute("style",
                    "left:" + dfdec1.format(100.0 * w.bounds.getX() / width) + "%;" + " top:"
                            + dfdec1.format(100.0 * w.bounds.getY() / height) + "%;" + " font-size:"
                            + dfdec1.format(100.0 * w.fontsize / this.fontmin) + "%");
            xml.writeCharacters(w.label);
            xml.writeEndElement();
        }
        xml.writeCharacters("\n");
        xml.writeEndDocument();
        xml.flush();
        xml.close();
        writer.flush();
    }

}
