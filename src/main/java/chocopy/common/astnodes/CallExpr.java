package chocopy.common.astnodes;

import chocopy.common.analysis.NodeAnalyzer;
import java_cup.runtime.ComplexSymbolFactory.Location;

import java.util.List;

/** A function call. */
public class CallExpr extends Expr {

    /** The called function. */
    public final Identifier function;
    /** The actual parameter expressions. */
    public final List<Expr> args;

    /** AST for FUNCTION(ARGS) at [LEFT..RIGHT]. */
    public CallExpr(Location left, Location right, Identifier function, List<Expr> args) {
        super(left, right);
        this.function = function;
        this.args = args;
    }

    public <T> T dispatch(NodeAnalyzer<T> analyzer) {
        return analyzer.analyze(this);
    }
}
