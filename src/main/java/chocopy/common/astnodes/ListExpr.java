package chocopy.common.astnodes;

import chocopy.common.analysis.NodeAnalyzer;
import java_cup.runtime.ComplexSymbolFactory.Location;

import java.util.List;

/** List displays. */
public final class ListExpr extends Expr {

    /** List of element expressions. */
    public final List<Expr> elements;

    /** The AST for [ ELEMENTS ]. spanning source locations [LEFT..RIGHT]. */
    public ListExpr(Location left, Location right, List<Expr> elements) {
        super(left, right);
        this.elements = elements;
    }

    public <T> T dispatch(NodeAnalyzer<T> analyzer) {
        return analyzer.analyze(this);
    }
}
