// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.analysis;

import com.starrocks.common.AnalysisException;
import com.starrocks.thrift.TExprNode;
import com.starrocks.thrift.TExprNodeType;

// DictMappingExpr.
// The original expression will be rewritten as a dictionary mapping function in the global field optimization.
// child(0) was input lowcardinality dictionary column (input was ID type).
// child(1) was origin expr (input was string type).
// 
// in Global Dictionary Optimization. The process of constructing a dictionary mapping requires 
// a new dictionary to be constructed using the global dictionary columns as input columns. 
// So BE needs to know the original expressions.
public class DictMappingExpr extends Expr {

    public DictMappingExpr(Expr ref, Expr call) {
        super(ref);
        this.addChild(ref);
        this.addChild(call);
    }

    protected DictMappingExpr(DictMappingExpr other) {
        super(other);
    }

    @Override
    protected void analyzeImpl(Analyzer analyzer) throws AnalysisException {

    }

    @Override
    protected String toSqlImpl() {
        return "DictExpr(" + this.getChild(0).toSqlImpl() + ",[" + this.getChild(1).toSqlImpl()  + "])";
    }

    @Override
    protected void toThrift(TExprNode msg) {
        msg.setNode_type(TExprNodeType.DICT_EXPR);
    }

    @Override
    public Expr clone() {
        return new DictMappingExpr(this);
    }
}
