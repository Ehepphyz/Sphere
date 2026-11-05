package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.ByteBuf;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfSerializer;

/** One operation of a content stream: its operator and operands (and an inline image's data). */
public final class Op {

    public final String name;
    public final PdfObject[] args;
    /** For BI: the image dictionary and its bytes exactly as written. */
    public final PdfDict inlineDict;
    public final byte[] inlineData;

    public Op(String name, PdfObject[] args) {
        this(name, args, null, null);
    }

    public Op(String name, PdfObject[] args, PdfDict inlineDict, byte[] inlineData) {
        this.name = name;
        this.args = args;
        this.inlineDict = inlineDict;
        this.inlineData = inlineData;
    }

    /** Writes the operation back in content stream syntax. */
    public void write(ByteBuf out) {
        if (inlineDict != null) {
            out.put("BI");
            for (var e : inlineDict.entries()) {
                out.put(' ');
                PdfSerializer.writeName(e.getKey(), out);
                out.put(' ');
                PdfSerializer.write(e.getValue(), out);
            }
            out.put(" ID ");
            out.put(inlineData);
            out.put("\nEI");
            return;
        }
        for (PdfObject a : args) {
            PdfSerializer.write(a, out);
            out.put(' ');
        }
        out.put(name);
    }

    public float f(int i) {
        return i < args.length && args[i] instanceof com.sphere.components.spdf.cos.PdfNumber n ? n.floatValue() : 0f;
    }

    public String nameArg(int i) {
        return i < args.length && args[i] instanceof PdfName n ? n.name : null;
    }

    @Override
    public String toString() {
        ByteBuf b = new ByteBuf();
        write(b);
        return b.toString();
    }
}
