/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package build.tools.fixuppandoc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixup HTML generated by pandoc.
 *
 * <h2>{@code <html>}</h2>
 *
 * Replace the existing element with {@code <html lang="en">}, removing references to XML.
 *
 * <h2>{@code <main>}</h2>
 *
 * {@code <main>} is inserted if palpable content is found that is not with a
 * section such as {@code header},  {@code footer},  {@code aside}.
 *
 * {@code </main>} is inserted if {@code <main>} was inserted and a section
 * is started that should not be included in the main section.
 *
 * <h2>Tables: row headings</h2>
 *
 * For simple tables, as typically generated by _pandoc_, determine the column
 * whose contents are unique, and convert the cells in that column to be header
 * cells with {@code scope="row"}. In case of ambiguity, a column containing a
 * {@code <th>} whose contents begin with <em>name</em> is preferred.
 * When converting the cell, the {@code style} attribute will be updated to
 * specify {@code font-weight: normal}, and if there is not already an explicit
 * setting for {@code text-align}, then the style will be updated to include
 * {@code text-align:left;}.
 *
 * These rules do not apply if the table contains any cells that include
 * a setting for the {@code scope} attribute, or if the table contains
 * spanning cells or nested tables.
 *
 * <h2>{@code <meta name="generator">}</h2>
 *
 * Update the content string, to indicate it has been processed by this program.
 *
 * <h2>{@code <nav id="TOC">}</h2>
 *
 * Set attribute {@code title="Table Of Contents"}
 *
 */
public class Main {
    /**
     * Runs the program.
     *
     * <pre>
     *     java build.tools.fixuppandoc.Main [-o output-file] [input-file]
     * </pre>
     *
     * If no input file is specified, the program will read from standard input.
     * If no output file is specified, the program will write to standard output.
     * Any error messages will be written to the standard error stream.
     *
     * @param args the command-line arguments
     */
    public static void main(String... args) {
        try {
            new Main().run(args);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e);
            System.exit(1);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run(String... args) throws IOException {
        Path inFile = null;
        Path outFile = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-o") && i + 1 < args.length) {
                outFile = Path.of(args[++i]);
            } else if (arg.startsWith("-")) {
                throw new IllegalArgumentException(arg);
            } else if (inFile == null) {
                inFile = Path.of(arg);
            } else {
                throw new IllegalArgumentException(arg);
            }
        }

        new Fixup().run(inFile, outFile);
    }

    /**
     * A class to read HTML, copying input to output, modifying
     * fragments as needed.
     */
    class Fixup extends HtmlParser {
        /** The output stream. */
        PrintWriter out;

        /** A stream for reporting errors. */
        PrintStream err = System.err;

        /**
         * Flag to indicate when {@code <main>} is permitted around palpable content.
         * Set within {@code <body>}; disabled within elements in which {@code <main>}
         * is not permitted.
         */
        boolean allowMain = false;

        /**
         * Flag to indicate that {@code <main>} is required.
         * Set on {@code <body>}; reset when {@code <main>} is either found or generated.
         */
        boolean needMain = false;

        /**
         * Flag to indicate that {@code </main>} is required.
         * Set if {@code <main>} is generated.
         * Reset when a start or end element is found that requires that {@code </main>}
         * needs to be generated if necessary.
         */
        boolean needEndMain = false;

        /**
         * Handler for {@code <table>} elements.
         */
        Table table;

        /**
         * Run the program, copying an input file to an output file.
         * If the input file is {@code null}, input is read from the standard input.
         * If the output file is {@code null}, output is written to the standard output.
         *
         * @param inFile the input file
         * @param outFile the output file
         * @throws IOException if an IO error occurs
         */
        void run(Path inFile, Path outFile) throws IOException {
            try (Writer out = openWriter(outFile)) {
                this.out = new PrintWriter(out);
                if (inFile != null) {
                    read(inFile);
                } else {
                    read(new BufferedReader(new InputStreamReader(System.in)));
                }
            }
        }

        /**
         * Returns a writer for a file, or for the standard output if the file is {@code null}.
         *
         * @param file the file
         * @return the writer
         * @throws IOException if an IO error occurs
         */
        private Writer openWriter(Path file) throws IOException {
            if (file != null) {
                return Files.newBufferedWriter(file);
            } else {
                return new BufferedWriter(new OutputStreamWriter(System.out) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                });
            }
        }

        @Override
        protected void error(Path file, int lineNumber, String message) {
            err.print(file == null ? "<stdin>" : file);
            if (lineNumber > 0) {
                err.print(":");
                err.print(lineNumber);
            }
            err.print(": ");
            err.println(message);
        }

        @Override
        protected void error(Path file, int lineNumber, Throwable t) {
            error(file, lineNumber, t.toString());
            t.printStackTrace(err);
        }

        /**
         * The buffer in which input is stored until an appropriate action can be determined.
         * Using the buffer ensures that the output exactly matches the input, except where
         * it is intentionally modified.
         */
        private StringBuilder buffer = new StringBuilder();

        @Override
        public int nextChar() throws IOException {
            if (ch > 0) {
                buffer.append((char) ch);
            }
            return super.nextChar();
        }

        @Override
        protected void doctype(String s) {
            flushBuffer();
        }

        @Override
        protected void startElement(String name, Map<String,String> attrs, boolean selfClosing) {
            switch (name) {
                case "html":
                    // replace the existing <html> fragment
                    out.write("<html lang=\"en\">");
                    buffer.setLength(0);
                    break;

                case "meta":
                    // update the meta-data for the generator
                    if (Objects.equals(attrs.get("name"), "generator")) {
                        out.write(buffer.toString()
                                .replaceAll("(content=\"[^\"]*)(\")", "$1,fixuphtml$2"));
                        buffer.setLength(0);
                    }
                    break;

                case "article":
                case "aside":
                case "footer":
                case "header":
                case "nav":
                    // starting one of these elements will terminate <main> if one is being
                    // inserted
                    if (needEndMain) {
                        out.write("</main>");
                        needEndMain = false;
                    }
                    // <main> is not permitted within these elements
                    allowMain = false;
                    if (name.equals("nav") && Objects.equals(attrs.get("id"), "TOC")) {
                        out.write(buffer.toString()
                                .replaceAll(">$", " title=\"Table Of Contents\">"));
                        buffer.setLength(0);
                    }
                    break;

                case "body":
                    // within <body>, <main> is both permitted and required
                    allowMain = true;
                    needMain = true;
                    break;

                case "main":
                    // an explicit <main> found in the input; no need to add one
                    needMain = false;
                    break;

                case "table":
                    // The entire content of a <table> is buffered, until it can be
                    // determined in which column of the table contains the cells
                    // that can be used to identify the row.
                    if (table == null) {
                        table = new Table();
                    } else {
                        // tables containing nested tables are not updated
                        table.simple = false;
                    }
                    table.nestDepth++;
                    break;

                case "thead":
                case "tbody":
                    if (table != null) {
                        table.endCell();
                    }
                    break;

                case "tr":
                    if (table != null) {
                        table.endCell();
                        table.nextCellColumnIndex = 0;
                    }
                    break;

                case "td":
                case "th":
                    if (table != null) {
                        if (attrs.containsKey("rowspan")
                                || attrs.containsKey("colspan")
                                || attrs.containsKey("scope")) {
                            // tables containing spanning cells and tables that already
                            // contain scope attributes are not updated
                            table.simple = false;
                        }
                        table.startCell(name);
                    }
                    break;
            }

            // by default, the content is deemed to be palpable content, and so
            // insert <main> if it is permitted and one is still required,
            // while also ensuring that it does not appear before <body>
            if (allowMain && needMain && !name.equals("body")) {
                out.write("<main>");
                needMain = false;
                needEndMain = true;
            }

            flushBuffer();
        }

        @Override
        protected void endElement(String name) {
            switch (name) {
                case "article":
                case "aside":
                case "footer":
                case "header":
                case "nav":
                    // The code does not handle nested elements of these kinds, but could.
                    // So, assuming they are not nested, ending these elements implies
                    // that <main> is once again permitted.
                    allowMain = true;
                    break;

                case "body":
                    // The document is nearly done; insert <main> if needed
                    if (needEndMain) {
                        out.write("</main>");
                        needEndMain = false;
                    }
                    break;

                case "table":
                    // if the table is finished, analyze it and write it out
                    if (table != null) {
                        if (--table.nestDepth == 0) {
                            table.add(buffer.toString());
                            table.write(out);
                            table = null;
                            buffer.setLength(0);
                        }
                    }
                    break;

                case "thead":
                case "tbody":
                case "tr":
                case "td":
                case "th":
                    // ending any of these elements implicity or explicitly ends the
                    // current cell
                    table.endCell();
                    break;

            }
            flushBuffer();
        }

        @Override
        protected void content(String content) {
            if (table != null) {
                table.content(content);
            } else if (allowMain && needMain && !content.isBlank()) {
                // insert <main> if required and if we have palpable content
                out.write("<main>");
                needMain = false;
                needEndMain = true;
            }
            flushBuffer();
        }

        @Override
        protected void comment(String comment) {
            flushBuffer();
        }

        /**
         * Flushes the buffer, either by adding it into a table, if one is
         * in progress, or by writing it out.
         */
        private void flushBuffer() {
            String s = buffer.toString();
            if (table != null) {
                table.add(s);
            } else {
                out.write(s);
            }
            buffer.setLength(0);

        }
    }

    /**
     * Storage for the content of a {@code <table>} element} until we can determine
     * whether we should add {@code scope="row"} to the cells in a given column,
     * and if so, which column.
     *
     * The column with the highest number of unique entries is selected;
     * in case of ambiguity, a column whose heading begins "name" is chosen.
     *
     * Only "simple" tables are supported. Tables with any of the following
     * features are not considered "simple" and will not be modified:
     * <ul>
     *     <li>Tables containing nested tables</li>
     *     <li>Tables containing cells that use "rowspan" and "colspan" attributes</li>
     *     <li>Tables containing cells that already use "scope" attributes</li>
     * </ul>
     */
    class Table {
        /**
         * A fragment of HTML in this table.
         */
        class Entry {
            /** The fragment. */
            final String html;
            /** The column for a {@code <td>} fragment, or -1. */
            final int column;

            Entry(String html, int column) {
                this.html = html;
                this.column = column;
            }
        }

        /** Whether or not this is a "simple" table. */
        boolean simple = true;

        /** The nesting depth of the current table, within enclosing tables. */
        int nestDepth;

        /** A list of the HTML fragments that make up this table. */
        List<Entry> entries;

        /** The plain text contents of each column, used to determine the primary column. */
        List<Set<String>> columnContents;

        /** The column index of the next cell to be found. */
        int nextCellColumnIndex;

        /** A flag to mark the start of a {@code <td>} cell. */
        boolean startTDCell;

        /** The column index of the current cell, or -1 if not in a cell. */
        int currCellColumnIndex;

        /** The plain text contents of the current column. */
        Set<String> currColumnContents;

        /** The plain text content of the current cell. */
        StringBuilder currCellContent;

        /** The kind ({@code th} or {@code td}) of the current cell. */
        String currCellKind;

        /**
         * The index of the column, if any, containing a heading beginning "name".
         * This column is given preferential treatment when deciding the primary column.
         */
        int nameColumn;

        Table() {
            entries = new ArrayList<>();
            columnContents = new ArrayList<>();
        }

        void startCell(String name) {
            endCell();
            startTDCell = name.equals("td");
            currCellColumnIndex = nextCellColumnIndex++;
            currColumnContents = getColumn(currCellColumnIndex);
            currCellContent = new StringBuilder();
            currCellKind = name;
        }

        void endCell() {
            if (currCellContent != null) {
                String c = currCellContent.toString().trim();
                if (Objects.equals(currCellKind, "th")
                        && c.toLowerCase(Locale.US).startsWith("name")) {
                    nameColumn = currCellColumnIndex;
                }
                currColumnContents.add(c);
                currCellContent = null;
                currCellColumnIndex = -1;
                currColumnContents = null;
            }
        }

        void content(String content) {
            if (currCellContent != null) {
                currCellContent.append(content);
            }
        }

        void add(String html) {
            int index = startTDCell ? currCellColumnIndex : -1;
            entries.add(new Entry(html, index));
            startTDCell = false;
        }

        void write(PrintWriter out) {
            int max = -1;
            int maxIndex = -1;
            int index = 0;
            for (Set<String> c : columnContents) {
                if (c.size() > max || c.size() == max && index == nameColumn) {
                    max = c.size();
                    maxIndex = index;
                }
                index++;
            }
            boolean updateEndTd = false;
            Pattern styleAttr = Pattern.compile("(?<before>.*style=\")(?<style>[^\"]*)(?<after>\".*)");
            for (Entry e : entries) {
                if (simple && e.column == maxIndex) {
                    String attrs = e.html.substring(3, e.html.length() - 1);
                    out.write("<th");
                    Matcher m = styleAttr.matcher(attrs);
                    if (m.matches()) {
                        out.write(m.group("before"));
                        out.write("font-weight: normal; ");
                        String style = m.group("style");
                        if (!style.contains("text-align")) {
                            out.write("text-align: left; ");
                        }
                        out.write(style);
                        out.write(m.group("after"));
                    } else {
                        out.write(" style=\"font-weight: normal; text-align:left;\" ");
                        out.write(attrs);
                    }
                    out.write(" scope=\"row\"");
                    out.write(">");
                    updateEndTd = true;
                } else if (updateEndTd && e.html.equalsIgnoreCase("</td>")) {
                    out.write("</th>");
                    updateEndTd = false;
                } else {
                    out.write(e.html);
                    if (updateEndTd && e.html.regionMatches(true, 0, "<td", 0, 3)) {
                        // a new cell has been started without explicitly closing the
                        // cell that was being updated
                        updateEndTd = false;
                    }
                }
            }
        }

        private Set<String> getColumn(int index) {
            while (columnContents.size() <= index) {
                columnContents.add(new LinkedHashSet<>());
            }

            return columnContents.get(index);
        }
    }

    /**
     * A basic HTML parser.
     * Override the protected methods as needed to get notified of significant items
     * in any file that is read.
     */
    abstract class HtmlParser {

        private Path file;
        private Reader in;
        protected int ch;
        private int lineNumber;
        private boolean inScript;
        private boolean xml;

        /**
         * Read a file.
         * @param file the file
         */
        void read(Path file) {
            try (Reader r = Files.newBufferedReader(file)) {
                this.file = file;
                read(r);
            } catch (IOException e) {
                error(file, -1, e);
            }
        }

        HtmlParser() { }

        /**
         * Read a stream.
         * @param r the stream
         */
        void read(Reader r) {
            try {
                this.in = r;
                StringBuilder content = new StringBuilder();

                startFile(file);
                try {
                    lineNumber = 1;
                    xml = false;
                    nextChar();

                    while (ch != -1) {
                        if (ch == '<') {
                            content(content.toString());
                            content.setLength(0);
                            html();
                        } else {
                            content.append((char) ch);
                            if (ch == '\n') {
                                content(content.toString());
                                content.setLength(0);
                            }
                            nextChar();
                        }
                    }
                } finally {
                    endFile();
                }
            } catch (IOException e) {
                error(file, lineNumber, e);
            } catch (Throwable t) {
                error(file, lineNumber, t);
                t.printStackTrace(System.err);
            }
        }

        protected int getLineNumber() {
            return lineNumber;
        }

        /**
         * Called when a file has been opened, before parsing begins.
         * This is always the first notification when reading a file.
         * This implementation does nothing.
         *
         * @param file the file
         */
        protected void startFile(Path file) { }

        /**
         * Called when the parser has finished reading a file.
         * This is always the last notification when reading a file,
         * unless any errors occur while closing the file.
         * This implementation does nothing.
         */
        protected void endFile() { }

        /**
         * Called when a doctype declaration is found, at the beginning of the file.
         * This implementation does nothing.
         * @param s the doctype declaration
         */
        protected void doctype(String s) { }

        /**
         * Called when the opening tag of an HTML element is encountered.
         * This implementation does nothing.
         * @param name the name of the tag
         * @param attrs the attribute
         * @param selfClosing whether or not this is a self-closing tag
         */
        protected void startElement(String name, Map<String,String> attrs, boolean selfClosing) { }

        /**
         * Called when the closing tag of an HTML tag is encountered.
         * This implementation does nothing.
         * @param name the name of the tag
         */
        protected void endElement(String name) { }

        /**
         * Called for sequences of character content.
         * @param content the character content
         */
        protected void content(String content) { }

        /**
         * Called for sequences of comment.
         * @param comment the comment
         */
        protected void comment(String comment) { }

        /**
         * Called when an error has been encountered.
         * @param file the file being read
         * @param lineNumber the line number of line containing the error
         * @param message a description of the error
         */
        protected abstract void error(Path file, int lineNumber, String message);

        /**
         * Called when an exception has been encountered.
         * @param file the file being read
         * @param lineNumber the line number of the line being read when the exception was found
         * @param t the exception
         */
        protected abstract void error(Path file, int lineNumber, Throwable t);

        protected int nextChar() throws IOException {
            ch = in.read();
            if (ch == '\n')
                lineNumber++;
            return ch;
        }

        /**
         * Read the start or end of an HTML tag, or an HTML comment
         * {@literal <identifier attrs> } or {@literal </identifier> }
         * @throws java.io.IOException if there is a problem reading the file
         */
        protected void html() throws IOException {
            nextChar();
            if (isIdentifierStart((char) ch)) {
                String name = readIdentifier().toLowerCase(Locale.US);
                Map<String,String> attrs = htmlAttrs();
                if (attrs != null) {
                    boolean selfClosing = false;
                    if (ch == '/') {
                        nextChar();
                        selfClosing = true;
                    }
                    if (ch == '>') {
                        nextChar();
                        startElement(name, attrs, selfClosing);
                        if (name.equals("script")) {
                            inScript = true;
                        }
                        return;
                    }
                }
            } else if (ch == '/') {
                nextChar();
                if (isIdentifierStart((char) ch)) {
                    String name = readIdentifier().toLowerCase(Locale.US);
                    skipWhitespace();
                    if (ch == '>') {
                        nextChar();
                        endElement(name);
                        if (name.equals("script")) {
                            inScript = false;
                        }
                        return;
                    }
                }
            } else if (ch == '!') {
                nextChar();
                if (ch == '-') {
                    nextChar();
                    if (ch == '-') {
                        nextChar();
                        StringBuilder comment = new StringBuilder();
                        while (ch != -1) {
                            int dash = 0;
                            while (ch == '-') {
                                dash++;
                                comment.append(ch);
                                nextChar();
                            }
                            // Strictly speaking, a comment should not contain "--"
                            // so dash > 2 is an error, dash == 2 implies ch == '>'
                            // See http://www.w3.org/TR/html-markup/syntax.html#syntax-comments
                            // for more details.
                            if (dash >= 2 && ch == '>') {
                                comment.setLength(comment.length() - 2);
                                comment(comment.toString());
                                nextChar();
                                return;
                            }

                            comment.append(ch);
                            nextChar();
                        }
                    }
                } else if (ch == '[') {
                    nextChar();
                    if (ch == 'C') {
                        nextChar();
                        if (ch == 'D') {
                            nextChar();
                            if (ch == 'A') {
                                nextChar();
                                if (ch == 'T') {
                                    nextChar();
                                    if (ch == 'A') {
                                        nextChar();
                                        if (ch == '[') {
                                            while (true) {
                                                nextChar();
                                                if (ch == ']') {
                                                    nextChar();
                                                    if (ch == ']') {
                                                        nextChar();
                                                        if (ch == '>') {
                                                            nextChar();
                                                            return;
                                                        }
                                                    }
                                                }
                                            }

                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    StringBuilder sb = new StringBuilder();
                    while (ch != -1 && ch != '>') {
                        sb.append((char) ch);
                        nextChar();
                    }
                    Pattern p = Pattern.compile("(?is)doctype\\s+html\\s?.*");
                    String s = sb.toString();
                    if (p.matcher(s).matches()) {
                        doctype(s);
                        return;
                    }
                }
            } else if (ch == '?') {
                nextChar();
                if (ch == 'x') {
                    nextChar();
                    if (ch == 'm') {
                        nextChar();
                        if (ch == 'l') {
                            Map<String,String> attrs = htmlAttrs();
                            if (ch == '?') {
                                nextChar();
                                if (ch == '>') {
                                    nextChar();
                                    xml = true;
                                    return;
                                }
                            }
                        }
                    }

                }
            }

            if (!inScript) {
                error(file, lineNumber, "bad html");
            }
        }

        /**
         * Read a series of HTML attributes, terminated by {@literal > }.
         * Each attribute is of the form {@literal identifier[=value] }.
         * "value" may be unquoted, single-quoted, or double-quoted.
         */
        private Map<String,String> htmlAttrs() throws IOException {
            Map<String, String> map = new LinkedHashMap<>();
            skipWhitespace();

            while (isIdentifierStart((char) ch)) {
                String name = readAttributeName().toLowerCase(Locale.US);
                skipWhitespace();
                String value = null;
                if (ch == '=') {
                    nextChar();
                    skipWhitespace();
                    if (ch == '\'' || ch == '"') {
                        char quote = (char) ch;
                        nextChar();
                        StringBuilder sb = new StringBuilder();
                        while (ch != -1 && ch != quote) {
                            sb.append((char) ch);
                            nextChar();
                        }
                        value = sb.toString() // hack to replace common entities
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&amp;", "&");
                        nextChar();
                    } else {
                        StringBuilder sb = new StringBuilder();
                        while (ch != -1 && !isUnquotedAttrValueTerminator((char) ch)) {
                            sb.append((char) ch);
                            nextChar();
                        }
                        value = sb.toString();
                    }
                    skipWhitespace();
                }
                map.put(name, value);
            }

            return map;
        }

        private boolean isIdentifierStart(char ch) {
            return Character.isUnicodeIdentifierStart(ch);
        }

        private String readIdentifier() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append((char) ch);
            nextChar();
            while (ch != -1 && Character.isUnicodeIdentifierPart(ch)) {
                sb.append((char) ch);
                nextChar();
            }
            return sb.toString();
        }

        private String readAttributeName() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append((char) ch);
            nextChar();
            while (ch != -1 && Character.isUnicodeIdentifierPart(ch)
                    || ch == '-'
                    || (xml || sb.toString().startsWith("xml")) && ch == ':') {
                sb.append((char) ch);
                nextChar();
            }
            return sb.toString();
        }

        private boolean isWhitespace(char ch) {
            return Character.isWhitespace(ch);
        }

        private void skipWhitespace() throws IOException {
            while (isWhitespace((char) ch)) {
                nextChar();
            }
        }

        private boolean isUnquotedAttrValueTerminator(char ch) {
            switch (ch) {
                case '\f': case '\n': case '\r': case '\t':
                case ' ':
                case '"': case '\'': case '`':
                case '=': case '<': case '>':
                    return true;
                default:
                    return false;
            }
        }
    }

}
