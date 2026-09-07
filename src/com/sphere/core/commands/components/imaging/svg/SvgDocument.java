package com.sphere.components.imaging.svg;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An SVG file parsed into a shape tree that draws onto any Graphics2D.
 *
 * Covers the subset that drawing tools actually emit: groups and transforms,
 * paths, the basic shapes, text, linear and radial gradients, and `use`.
 */
public final class SvgDocument {

    /** One drawable leaf, already flattened into device-independent user space. */
    private static final class Item {
        Shape shape;
        SvgStyle style;
        String text;
        Point2D textAnchor;
        AffineTransform transform;
    }

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Element> byId = new HashMap<>();
    private final Map<String, Paint> gradients = new HashMap<>();

    private double width = 0;
    private double height = 0;
    private AffineTransform viewBoxTransform = new AffineTransform();

    private SvgDocument() {
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int getElementCount() {
        return items.size();
    }

    // ---- loading -----------------------------------------------------------

    public static SvgDocument load(File file) throws Exception {
        return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    public static SvgDocument parse(String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // An SVG is untrusted input: no external entities, no doctype.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
            new InputSource(new ByteArrayInputStream(new byte[0])));

        Document dom = builder.parse(new InputSource(new StringReader(source)));
        Element root = dom.getDocumentElement();
        if (root == null || !localName(root).equals("svg")) {
            throw new IllegalArgumentException("The file has no <svg> root element.");
        }

        SvgDocument doc = new SvgDocument();
        doc.indexIds(root);
        doc.readSize(root);
        doc.collectGradients(root);
        doc.walk(root, new SvgStyle(), new AffineTransform());
        return doc;
    }

    private void indexIds(Element element) {
        final String id = element.getAttribute("id");
        if (!id.isEmpty()) {
            byId.put(id, element);
        }
        for (Element child : children(element)) {
            indexIds(child);
        }
    }

    private void readSize(Element root) {
        double w = SvgStyle.length(root.getAttribute("width"), 0);
        double h = SvgStyle.length(root.getAttribute("height"), 0);

        final String viewBox = root.getAttribute("viewBox");
        double[] box = null;
        if (!viewBox.isBlank()) {
            String[] parts = viewBox.trim().split("[,\\s]+");
            if (parts.length >= 4) {
                try {
                    box = new double[] {
                        Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3])
                    };
                } catch (NumberFormatException ignored) {
                    box = null;
                }
            }
        }

        if (w <= 0 && box != null) {
            w = box[2];
        }
        if (h <= 0 && box != null) {
            h = box[3];
        }
        if (w <= 0) {
            w = 300;
        }
        if (h <= 0) {
            h = 150;
        }
        width = w;
        height = h;

        if (box != null && box[2] > 0 && box[3] > 0) {
            // preserveAspectRatio defaults to xMidYMid meet: one uniform scale,
            // centered, which is what a viewer wants.
            final double scale = Math.min(w / box[2], h / box[3]);
            final double dx = (w - box[2] * scale) / 2.0;
            final double dy = (h - box[3] * scale) / 2.0;
            viewBoxTransform = new AffineTransform();
            viewBoxTransform.translate(dx, dy);
            viewBoxTransform.scale(scale, scale);
            viewBoxTransform.translate(-box[0], -box[1]);
        }
    }

    // ---- traversal ---------------------------------------------------------

    private void walk(Element element, SvgStyle inherited, AffineTransform parent) {
        final String name = localName(element);
        if (name.equals("defs") || name.equals("symbol") || name.equals("clipPath")
            || name.equals("mask") || name.equals("metadata") || name.equals("title")
            || name.equals("desc") || name.equals("style") || name.equals("script")) {
            return;
        }
        if ("none".equalsIgnoreCase(element.getAttribute("display"))) {
            return;
        }

        final SvgStyle style = applyStyle(inherited.clone(), element);
        final AffineTransform local = new AffineTransform(parent);
        if (name.equals("svg") && element.getParentNode() instanceof Document) {
            local.concatenate(viewBoxTransform);
        }
        local.concatenate(SvgTransform.parse(element.getAttribute("transform")));

        switch (name) {
            case "svg":
            case "g":
            case "a":
                for (Element child : children(element)) {
                    walk(child, style, local);
                }
                return;
            case "use": {
                final String href = reference(element);
                Element target = href == null ? null : byId.get(href);
                if (target != null && target != element) {
                    AffineTransform used = new AffineTransform(local);
                    used.translate(SvgStyle.length(element.getAttribute("x"), 0),
                                   SvgStyle.length(element.getAttribute("y"), 0));
                    walk(target, style, used);
                }
                return;
            }
            case "text":
            case "tspan": {
                addText(element, style, local);
                return;
            }
            default: {
                Shape shape = toShape(element, name);
                if (shape != null) {
                    Item item = new Item();
                    item.shape = shape;
                    item.style = style;
                    item.transform = local;
                    items.add(item);
                }
                for (Element child : children(element)) {
                    walk(child, style, local);
                }
            }
        }
    }

    private Shape toShape(Element e, String name) {
        switch (name) {
            case "path": {
                Path2D.Double path = SvgPathParser.parse(e.getAttribute("d"));
                if ("evenodd".equalsIgnoreCase(attributeOrStyle(e, "fill-rule"))) {
                    path.setWindingRule(Path2D.WIND_EVEN_ODD);
                }
                return path;
            }
            case "rect": {
                double x = SvgStyle.length(e.getAttribute("x"), 0);
                double y = SvgStyle.length(e.getAttribute("y"), 0);
                double w = SvgStyle.length(e.getAttribute("width"), 0);
                double h = SvgStyle.length(e.getAttribute("height"), 0);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                double rx = SvgStyle.length(e.getAttribute("rx"), 0);
                double ry = SvgStyle.length(e.getAttribute("ry"), rx);
                if (rx <= 0 && ry <= 0) {
                    return new Rectangle2D.Double(x, y, w, h);
                }
                if (rx <= 0) {
                    rx = ry;
                }
                if (ry <= 0) {
                    ry = rx;
                }
                return new RoundRectangle2D.Double(x, y, w, h, rx * 2, ry * 2);
            }
            case "circle": {
                double cx = SvgStyle.length(e.getAttribute("cx"), 0);
                double cy = SvgStyle.length(e.getAttribute("cy"), 0);
                double r = SvgStyle.length(e.getAttribute("r"), 0);
                return r <= 0 ? null
                    : new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
            }
            case "ellipse": {
                double cx = SvgStyle.length(e.getAttribute("cx"), 0);
                double cy = SvgStyle.length(e.getAttribute("cy"), 0);
                double rx = SvgStyle.length(e.getAttribute("rx"), 0);
                double ry = SvgStyle.length(e.getAttribute("ry"), 0);
                return (rx <= 0 || ry <= 0) ? null
                    : new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2);
            }
            case "line": {
                return new Line2D.Double(
                    SvgStyle.length(e.getAttribute("x1"), 0),
                    SvgStyle.length(e.getAttribute("y1"), 0),
                    SvgStyle.length(e.getAttribute("x2"), 0),
                    SvgStyle.length(e.getAttribute("y2"), 0));
            }
            case "polyline":
            case "polygon": {
                double[] pts = points(e.getAttribute("points"));
                if (pts.length < 4) {
                    return null;
                }
                Path2D.Double path = new Path2D.Double();
                path.moveTo(pts[0], pts[1]);
                for (int i = 2; i + 1 < pts.length; i += 2) {
                    path.lineTo(pts[i], pts[i + 1]);
                }
                if (name.equals("polygon")) {
                    path.closePath();
                }
                return path;
            }
            default:
                return null;
        }
    }

    private void addText(Element e, SvgStyle style, AffineTransform transform) {
        final StringBuilder content = new StringBuilder();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node node = kids.item(i);
            if (node.getNodeType() == Node.TEXT_NODE
                || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                content.append(node.getNodeValue());
            }
        }
        final String text = content.toString().replaceAll("\\s+", " ").trim();

        if (!text.isEmpty()) {
            Item item = new Item();
            item.text = text;
            item.style = style;
            item.transform = transform;
            item.textAnchor = new Point2D.Double(
                SvgStyle.length(e.getAttribute("x"), 0),
                SvgStyle.length(e.getAttribute("y"), 0));
            items.add(item);
        }

        for (Element child : children(e)) {
            if (localName(child).equals("tspan")) {
                walk(child, style, transform);
            }
        }
    }

    // ---- style -------------------------------------------------------------

    private SvgStyle applyStyle(SvgStyle style, Element e) {
        set(style, "fill", attributeOrStyle(e, "fill"));
        set(style, "stroke", attributeOrStyle(e, "stroke"));
        set(style, "stroke-width", attributeOrStyle(e, "stroke-width"));
        set(style, "opacity", attributeOrStyle(e, "opacity"));
        set(style, "fill-opacity", attributeOrStyle(e, "fill-opacity"));
        set(style, "stroke-opacity", attributeOrStyle(e, "stroke-opacity"));
        set(style, "stroke-linecap", attributeOrStyle(e, "stroke-linecap"));
        set(style, "stroke-linejoin", attributeOrStyle(e, "stroke-linejoin"));
        set(style, "stroke-miterlimit", attributeOrStyle(e, "stroke-miterlimit"));
        set(style, "stroke-dasharray", attributeOrStyle(e, "stroke-dasharray"));
        set(style, "stroke-dashoffset", attributeOrStyle(e, "stroke-dashoffset"));
        set(style, "fill-rule", attributeOrStyle(e, "fill-rule"));
        set(style, "font-family", attributeOrStyle(e, "font-family"));
        set(style, "font-size", attributeOrStyle(e, "font-size"));
        set(style, "font-weight", attributeOrStyle(e, "font-weight"));
        set(style, "font-style", attributeOrStyle(e, "font-style"));
        set(style, "text-anchor", attributeOrStyle(e, "text-anchor"));
        return style;
    }

    private static void set(SvgStyle style, String property, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        final String v = value.trim();
        switch (property) {
            case "fill": style.fill = v; break;
            case "stroke": style.stroke = v; break;
            case "stroke-width": style.strokeWidth = SvgStyle.length(v, style.strokeWidth); break;
            case "opacity": style.opacity = ratio(v, style.opacity); break;
            case "fill-opacity": style.fillOpacity = ratio(v, style.fillOpacity); break;
            case "stroke-opacity": style.strokeOpacity = ratio(v, style.strokeOpacity); break;
            case "fill-rule":
                style.windingRule = "evenodd".equalsIgnoreCase(v)
                    ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO;
                break;
            case "stroke-linecap":
                style.lineCap = switch (v.toLowerCase(Locale.ROOT)) {
                    case "round" -> java.awt.BasicStroke.CAP_ROUND;
                    case "square" -> java.awt.BasicStroke.CAP_SQUARE;
                    default -> java.awt.BasicStroke.CAP_BUTT;
                };
                break;
            case "stroke-linejoin":
                style.lineJoin = switch (v.toLowerCase(Locale.ROOT)) {
                    case "round" -> java.awt.BasicStroke.JOIN_ROUND;
                    case "bevel" -> java.awt.BasicStroke.JOIN_BEVEL;
                    default -> java.awt.BasicStroke.JOIN_MITER;
                };
                break;
            case "stroke-miterlimit": style.miterLimit = SvgStyle.length(v, style.miterLimit); break;
            case "stroke-dashoffset": style.dashOffset = SvgStyle.length(v, style.dashOffset); break;
            case "stroke-dasharray": {
                if ("none".equalsIgnoreCase(v)) {
                    style.dashArray = null;
                    break;
                }
                String[] parts = v.split("[,\\s]+");
                List<Float> dashes = new ArrayList<>();
                for (String p : parts) {
                    if (!p.isBlank()) {
                        dashes.add((float) SvgStyle.length(p, 0));
                    }
                }
                if (dashes.isEmpty()) {
                    style.dashArray = null;
                } else {
                    float[] out = new float[dashes.size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = dashes.get(i);
                    }
                    style.dashArray = out;
                }
                break;
            }
            case "font-family": {
                String family = v.split(",")[0].trim().replace("'", "").replace("\"", "");
                if (!family.isEmpty()) {
                    style.fontFamily = family;
                }
                break;
            }
            case "font-size": style.fontSize = SvgStyle.length(v, style.fontSize); break;
            case "font-weight": {
                String lower = v.toLowerCase(Locale.ROOT);
                style.bold = lower.equals("bold") || lower.equals("bolder");
                if (!style.bold) {
                    try {
                        style.bold = Integer.parseInt(lower) >= 600;
                    } catch (NumberFormatException ignored) {
                        // keep whatever was inherited
                    }
                }
                break;
            }
            case "font-style":
                style.italic = v.toLowerCase(Locale.ROOT).startsWith("italic")
                            || v.toLowerCase(Locale.ROOT).startsWith("oblique");
                break;
            case "text-anchor": style.textAnchor = v.toLowerCase(Locale.ROOT); break;
            default:
                break;
        }
    }

    private static double ratio(String text, double fallback) {
        try {
            String t = text.trim();
            if (t.endsWith("%")) {
                return Double.parseDouble(t.substring(0, t.length() - 1)) / 100.0;
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The `style` attribute wins over the matching presentation attribute. */
    private static String attributeOrStyle(Element e, String property) {
        final String inline = styleProperty(e.getAttribute("style"), property);
        if (inline != null) {
            return inline;
        }
        final String attribute = e.getAttribute(property);
        return attribute.isEmpty() ? null : attribute;
    }

    private static String styleProperty(String style, String property) {
        if (style == null || style.isBlank()) {
            return null;
        }
        for (String declaration : style.split(";")) {
            final int colon = declaration.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (declaration.substring(0, colon).trim().equalsIgnoreCase(property)) {
                String value = declaration.substring(colon + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    // ---- gradients ---------------------------------------------------------

    private void collectGradients(Element root) {
        for (Element e : descendants(root)) {
            final String name = localName(e);
            if (!name.equals("linearGradient") && !name.equals("radialGradient")) {
                continue;
            }
            final String id = e.getAttribute("id");
            if (id.isEmpty()) {
                continue;
            }
            Paint paint = buildGradient(e, name.equals("linearGradient"));
            if (paint != null) {
                gradients.put(id, paint);
            }
        }
    }

    private Paint buildGradient(Element e, boolean linear) {
        List<Float> stops = new ArrayList<>();
        List<Color> colors = new ArrayList<>();

        Element source = e;
        if (childStops(e).isEmpty()) {
            // A gradient may keep its stops in another one it references.
            final String href = reference(e);
            Element inherited = href == null ? null : byId.get(href);
            if (inherited != null) {
                source = inherited;
            }
        }

        for (Element stop : childStops(source)) {
            float offset = (float) ratio(
                stop.getAttribute("offset").isEmpty() ? "0" : stop.getAttribute("offset"), 0);
            String color = attributeOrStyle(stop, "stop-color");
            String alpha = attributeOrStyle(stop, "stop-opacity");
            Color c = SvgStyle.toColor(color == null ? "#000000" : color,
                                       alpha == null ? 1.0 : ratio(alpha, 1.0));
            if (c == null) {
                c = Color.BLACK;
            }
            // LinearGradientPaint refuses equal or unsorted fractions.
            if (!stops.isEmpty() && offset <= stops.get(stops.size() - 1)) {
                offset = stops.get(stops.size() - 1) + 0.0001f;
            }
            stops.add(Math.max(0f, Math.min(1f, offset)));
            colors.add(c);
        }

        if (colors.isEmpty()) {
            return null;
        }
        if (colors.size() == 1) {
            return colors.get(0);
        }

        float[] fractions = new float[stops.size()];
        for (int i = 0; i < fractions.length; i++) {
            fractions[i] = stops.get(i);
        }
        Color[] palette = colors.toArray(new Color[0]);

        try {
            if (linear) {
                double x1 = SvgStyle.length(orDefault(e, "x1", "0"), 0);
                double y1 = SvgStyle.length(orDefault(e, "y1", "0"), 0);
                double x2 = SvgStyle.length(orDefault(e, "x2", "1"), 1);
                double y2 = SvgStyle.length(orDefault(e, "y2", "0"), 0);
                if (x1 == x2 && y1 == y2) {
                    return palette[palette.length - 1];
                }
                return new LinearGradientPaint(
                    new Point2D.Double(x1, y1), new Point2D.Double(x2, y2),
                    fractions, palette,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            }
            double cx = SvgStyle.length(orDefault(e, "cx", "0.5"), 0.5);
            double cy = SvgStyle.length(orDefault(e, "cy", "0.5"), 0.5);
            double r = SvgStyle.length(orDefault(e, "r", "0.5"), 0.5);
            if (r <= 0) {
                return palette[palette.length - 1];
            }
            return new RadialGradientPaint(
                new Point2D.Double(cx, cy), (float) r,
                fractions, palette,
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        } catch (RuntimeException e2) {
            return palette[palette.length - 1];
        }
    }

    private static String orDefault(Element e, String name, String fallback) {
        final String v = e.getAttribute(name);
        return v.isEmpty() ? fallback : v;
    }

    private static List<Element> childStops(Element e) {
        List<Element> out = new ArrayList<>();
        for (Element child : children(e)) {
            if (localName(child).equals("stop")) {
                out.add(child);
            }
        }
        return out;
    }

    /**
     * A gradient is defined in a unit square by default, so it is stretched over
     * the shape it paints rather than used as absolute coordinates.
     */
    private Paint resolvePaint(String value, double alpha, Shape shape) {
        if (value != null && value.trim().startsWith("url(")) {
            final String id = referenceId(value);
            Paint paint = id == null ? null : gradients.get(id);
            if (paint == null) {
                return null;
            }
            if (paint instanceof Color) {
                return paint;
            }
            Rectangle2D bounds = shape.getBounds2D();
            if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
                return null;
            }
            AffineTransform box = new AffineTransform();
            box.translate(bounds.getX(), bounds.getY());
            box.scale(bounds.getWidth(), bounds.getHeight());
            if (paint instanceof LinearGradientPaint g) {
                return new LinearGradientPaint(
                    g.getStartPoint(), g.getEndPoint(), g.getFractions(), g.getColors(),
                    g.getCycleMethod(), g.getColorSpace(), box);
            }
            if (paint instanceof RadialGradientPaint g) {
                return new RadialGradientPaint(
                    g.getCenterPoint(), g.getRadius(), g.getFocusPoint(),
                    g.getFractions(), g.getColors(), g.getCycleMethod(),
                    g.getColorSpace(), box);
            }
            return paint;
        }
        return SvgStyle.toColor(value, alpha);
    }

    private static String referenceId(String value) {
        final int open = value.indexOf('(');
        final int close = value.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String id = value.substring(open + 1, close).trim().replace("\"", "").replace("'", "");
        return id.startsWith("#") ? id.substring(1) : id;
    }

    private static String reference(Element e) {
        String href = e.getAttribute("xlink:href");
        if (href.isEmpty()) {
            href = e.getAttribute("href");
        }
        if (href.isEmpty()) {
            return null;
        }
        return href.startsWith("#") ? href.substring(1) : href;
    }

    // ---- drawing -----------------------------------------------------------

    /** Draws the document at its own size, onto whatever transform g already has. */
    public void paint(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                           RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                           RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (Item item : items) {
            if (item.text != null) {
                paintText(g, item);
            } else {
                paintShape(g, item);
            }
        }
    }

    private void paintShape(Graphics2D g, Item item) {
        final Shape shape = item.transform.createTransformedShape(item.shape);
        final SvgStyle s = item.style;

        if (s.hasFill()) {
            Paint paint = resolvePaint(s.fill, s.opacity * s.fillOpacity, shape);
            if (paint != null) {
                g.setPaint(paint);
                g.fill(shape);
            }
        }
        if (s.hasStroke()) {
            Paint paint = resolvePaint(s.stroke, s.opacity * s.strokeOpacity, shape);
            if (paint != null) {
                g.setPaint(paint);
                // The transform may scale the stroke; measure it rather than guess.
                g.setStroke(s.toStroke(averageScale(item.transform)));
                g.draw(shape);
            }
        }
    }

    private void paintText(Graphics2D g, Item item) {
        final SvgStyle s = item.style;
        int flags = Font.PLAIN;
        if (s.bold) {
            flags |= Font.BOLD;
        }
        if (s.italic) {
            flags |= Font.ITALIC;
        }
        Font font = new Font(s.fontFamily, flags, 12)
            .deriveFont((float) Math.max(s.fontSize, 1.0));

        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector glyphs = font.createGlyphVector(frc, item.text);
        Shape outline = glyphs.getOutline();
        final double advance = glyphs.getLogicalBounds().getWidth();

        double dx = 0;
        if ("middle".equals(s.textAnchor)) {
            dx = -advance / 2.0;
        } else if ("end".equals(s.textAnchor)) {
            dx = -advance;
        }

        AffineTransform place = new AffineTransform(item.transform);
        place.translate(item.textAnchor.getX() + dx, item.textAnchor.getY());
        Shape shape = place.createTransformedShape(outline);

        if (s.hasFill()) {
            Paint paint = resolvePaint(s.fill, s.opacity * s.fillOpacity, shape);
            if (paint != null) {
                g.setPaint(paint);
                g.fill(shape);
            }
        }
        if (s.hasStroke()) {
            Paint paint = resolvePaint(s.stroke, s.opacity * s.strokeOpacity, shape);
            if (paint != null) {
                g.setPaint(paint);
                g.setStroke(s.toStroke(averageScale(item.transform)));
                g.draw(shape);
            }
        }
    }

    private static double averageScale(AffineTransform t) {
        final double sx = Math.hypot(t.getScaleX(), t.getShearY());
        final double sy = Math.hypot(t.getShearX(), t.getScaleY());
        final double scale = (sx + sy) / 2.0;
        return scale <= 0 ? 1.0 : scale;
    }

    /** Renders to a bitmap at the requested pixel size, on a transparent ground. */
    public BufferedImage rasterize(int pixelWidth, int pixelHeight) {
        final int w = Math.max(1, pixelWidth);
        final int h = Math.max(1, pixelHeight);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(w / width, h / height);
            paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    public BufferedImage rasterize(double scale) {
        return rasterize((int) Math.round(width * scale), (int) Math.round(height * scale));
    }

    // ---- DOM helpers -------------------------------------------------------

    private static String localName(Element e) {
        final String tag = e.getTagName();
        final int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    private static List<Element> descendants(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Element child : children(parent)) {
            out.add(child);
            out.addAll(descendants(child));
        }
        return out;
    }

    private static double[] points(String text) {
        if (text == null || text.isBlank()) {
            return new double[0];
        }
        String[] parts = text.trim().split("[,\\s]+");
        List<Double> values = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            try {
                values.add(Double.parseDouble(part));
            } catch (NumberFormatException ignored) {
                // Stop at the first token that is not a number, as renderers do.
                break;
            }
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** Attribute names present on an element, for the inspector panel. */
    public static List<String> attributeNames(Element e) {
        List<String> names = new ArrayList<>();
        NamedNodeMap map = e.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            names.add(map.item(i).getNodeName());
        }
        return names;
    }
}
