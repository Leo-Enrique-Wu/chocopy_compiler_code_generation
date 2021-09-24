package chocopy.common.astnodes;

import chocopy.common.analysis.NodeAnalyzer;
import java_cup.runtime.ComplexSymbolFactory.Location;

import java.util.List;

/** Method calls. */
public class MethodCallExpr extends Expr {

    /** Expression for the bound method to be called. */
    public final MemberExpr method;
    /** Actual parameters. */
    public final List<Expr> args;

    /** The AST for METHOD(ARGS). spanning source locations [LEFT..RIGHT]. */
    public MethodCallExpr(Location left, Location right, MemberExpr method, List<Expr> args) {
        super(left, right);
        this.method = method;
        this.args = args;
    }

    public <T> T dispatch(NodeAnalyzer<T> analyzer) {
        return analyzer.analyze(this);
    }
}
