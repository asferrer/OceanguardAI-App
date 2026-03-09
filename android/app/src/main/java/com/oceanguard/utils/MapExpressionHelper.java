package com.oceanguard.ai.utils;

import org.maplibre.compose.expressions.ast.Expression;
import org.maplibre.compose.expressions.dsl.DecisionKt;
import org.maplibre.compose.expressions.value.BooleanValue;

/**
 * Java helper to call MapLibre expression functions that are inaccessible
 * from Kotlin due to name resolution conflicts.
 */
public final class MapExpressionHelper {
    private MapExpressionHelper() {}

    /**
     * Negates a boolean expression. Wraps {@code DecisionKt.notOperator()}.
     */
    public static Expression<BooleanValue> not(Expression<? extends BooleanValue> expr) {
        return DecisionKt.notOperator(expr);
    }
}
