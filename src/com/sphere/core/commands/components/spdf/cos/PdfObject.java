package com.sphere.components.spdf.cos;

/**
 * Any value of a PDF file: the COS layer every other part of spdf reads and
 * writes. Direct objects are mutable (a dictionary, an array, a stream); an
 * indirect object is reached through a {@link PdfRef}.
 */
public interface PdfObject {

    /** A deep copy: dictionaries, arrays and streams are copied, references stay references. */
    PdfObject copy();
}
