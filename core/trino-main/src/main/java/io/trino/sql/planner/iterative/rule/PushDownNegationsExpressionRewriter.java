/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableMap;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.RealType;
import io.trino.spi.type.Type;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.ComparisonExpression.Operator;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.ExpressionRewriter;
import io.trino.sql.ir.ExpressionTreeRewriter;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.NodeRef;
import io.trino.sql.ir.NotExpression;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.IS_DISTINCT_FROM;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.ir.IrUtils.combinePredicates;
import static io.trino.sql.ir.IrUtils.extractPredicates;
import static java.util.Objects.requireNonNull;

public final class PushDownNegationsExpressionRewriter
{
    public static Expression pushDownNegations(Expression expression, Map<NodeRef<Expression>, Type> expressionTypes)
    {
        return ExpressionTreeRewriter.rewriteWith(new Visitor(expressionTypes), expression);
    }

    private PushDownNegationsExpressionRewriter() {}

    private static class Visitor
            extends ExpressionRewriter<Void>
    {
        private final Map<NodeRef<Expression>, Type> expressionTypes;

        public Visitor(Map<NodeRef<Expression>, Type> expressionTypes)
        {
            this.expressionTypes = ImmutableMap.copyOf(requireNonNull(expressionTypes, "expressionTypes is null"));
        }

        @Override
        public Expression rewriteNotExpression(NotExpression node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            if (node.getValue() instanceof LogicalExpression child) {
                List<Expression> predicates = extractPredicates(child);
                List<Expression> negatedPredicates = predicates.stream().map(predicate -> treeRewriter.rewrite((Expression) new NotExpression(predicate), context)).collect(toImmutableList());
                return combinePredicates(child.getOperator().flip(), negatedPredicates);
            }
            if (node.getValue() instanceof ComparisonExpression child && child.getOperator() != IS_DISTINCT_FROM) {
                Operator operator = child.getOperator();
                Expression left = child.getLeft();
                Expression right = child.getRight();
                Type leftType = expressionTypes.get(NodeRef.of(left));
                Type rightType = expressionTypes.get(NodeRef.of(right));
                checkState(leftType != null && rightType != null, "missing type for expression");
                if ((typeHasNaN(leftType) || typeHasNaN(rightType)) && (
                        operator == GREATER_THAN_OR_EQUAL ||
                                operator == GREATER_THAN ||
                                operator == LESS_THAN_OR_EQUAL ||
                                operator == LESS_THAN)) {
                    return new NotExpression(new ComparisonExpression(operator, treeRewriter.rewrite(left, context), treeRewriter.rewrite(right, context)));
                }
                return new ComparisonExpression(operator.negate(), treeRewriter.rewrite(left, context), treeRewriter.rewrite(right, context));
            }
            if (node.getValue() instanceof NotExpression child) {
                return treeRewriter.rewrite(child.getValue(), context);
            }

            return new NotExpression(treeRewriter.rewrite(node.getValue(), context));
        }

        private boolean typeHasNaN(Type type)
        {
            return type instanceof DoubleType || type instanceof RealType;
        }
    }
}
