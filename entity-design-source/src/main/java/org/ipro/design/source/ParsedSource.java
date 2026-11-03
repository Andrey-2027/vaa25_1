package org.ipro.design.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import java.util.List;

/** One parsed file: its text, hash and syntax tree, with character positions for every node. */
public final class ParsedSource {
    private final String fileName, text, sha256;
    private final CompilationUnitTree unit;
    private final SourcePositions positions;

    ParsedSource(String fileName, String text, String sha256, CompilationUnitTree unit, SourcePositions positions) {
        this.fileName = fileName; this.text = text; this.sha256 = sha256; this.unit = unit; this.positions = positions;
    }
    public String fileName() { return fileName; }
    /** The text exactly as read; all positions index into it. */
    public String text() { return text; }
    /** SHA-256 of the file bytes as read, lower-case hex. */
    public String sha256() { return sha256; }
    public CompilationUnitTree unit() { return unit; }
    public String packageName() { return unit.getPackageName() == null ? "" : unit.getPackageName().toString(); }
    public List<? extends ImportTree> imports() { return unit.getImports(); }
    public int start(Tree tree) { return (int) positions.getStartPosition(unit, tree); }
    public int end(Tree tree) { return (int) positions.getEndPosition(unit, tree); }
    public int line(Tree tree) { return (int) unit.getLineMap().getLineNumber(start(tree)); }
    /** The source text of a node, exactly as written. */
    public String source(Tree tree) { return text.substring(start(tree), end(tree)); }
    /** Top-level type declarations in source order. */
    public List<ClassTree> topLevelTypes() {
        return unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast).toList();
    }
}
