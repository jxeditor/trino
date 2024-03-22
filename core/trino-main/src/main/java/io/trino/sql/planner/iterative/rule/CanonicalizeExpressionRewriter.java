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

import com.google.common.collect.ImmutableList;
import io.trino.spi.function.CatalogSchemaFunctionName;
import io.trino.spi.function.OperatorType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.Arithmetic;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.ExpressionRewriter;
import io.trino.sql.ir.ExpressionTreeRewriter;
import io.trino.sql.ir.Reference;

import static io.trino.metadata.GlobalFunctionCatalog.builtinFunctionName;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.sql.ir.Arithmetic.Operator.ADD;
import static io.trino.sql.ir.Arithmetic.Operator.MULTIPLY;

public final class CanonicalizeExpressionRewriter
{
    public static Expression canonicalizeExpression(Expression expression, PlannerContext plannerContext)
    {
        return ExpressionTreeRewriter.rewriteWith(new Visitor(plannerContext), expression);
    }

    private CanonicalizeExpressionRewriter() {}

    public static Expression rewrite(Expression expression, PlannerContext plannerContext)
    {
        if (expression instanceof Reference) {
            return expression;
        }

        return ExpressionTreeRewriter.rewriteWith(new Visitor(plannerContext), expression);
    }

    private static class Visitor
            extends ExpressionRewriter<Void>
    {
        private final PlannerContext plannerContext;

        public Visitor(PlannerContext plannerContext)
        {
            this.plannerContext = plannerContext;
        }

        @SuppressWarnings("ArgumentSelectionDefectChecker")
        @Override
        public Expression rewriteComparison(Comparison node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            // if we have a comparison of the form <constant> <op> <expr>, normalize it to
            // <expr> <op-flipped> <constant>
            if (isConstant(node.left()) && !isConstant(node.right())) {
                node = new Comparison(node.operator().flip(), node.right(), node.left());
            }

            return treeRewriter.defaultRewrite(node, context);
        }

        @SuppressWarnings("ArgumentSelectionDefectChecker")
        @Override
        public Expression rewriteArithmetic(Arithmetic node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            if (node.operator() == MULTIPLY || node.operator() == ADD) {
                // if we have a operation of the form <constant> [+|*] <expr>, normalize it to
                // <expr> [+|*] <constant>
                if (isConstant(node.left()) && !isConstant(node.right())) {
                    node = new Arithmetic(
                            plannerContext.getMetadata().resolveOperator(
                                    switch (node.operator()) {
                                        case ADD -> OperatorType.ADD;
                                        case MULTIPLY -> OperatorType.MULTIPLY;
                                        default -> throw new IllegalStateException("Unexpected value: " + node.operator());
                                    },
                                    ImmutableList.of(
                                            node.function().getSignature().getArgumentType(1),
                                            node.function().getSignature().getArgumentType(0))),
                            node.operator(),
                            node.right(),
                            node.left());
                }
            }

            return treeRewriter.defaultRewrite(node, context);
        }

        @Override
        public Expression rewriteCall(Call node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            CatalogSchemaFunctionName functionName = node.function().getName();
            if (functionName.equals(builtinFunctionName("date")) && node.arguments().size() == 1) {
                Expression argument = node.arguments().get(0);
                Type argumentType = argument.type();
                if (argumentType instanceof TimestampType
                        || argumentType instanceof TimestampWithTimeZoneType
                        || argumentType instanceof VarcharType) {
                    // prefer `CAST(x as DATE)` to `date(x)`, see e.g. UnwrapCastInComparison
                    return new Cast(treeRewriter.rewrite(argument, context), DATE);
                }
            }

            return treeRewriter.defaultRewrite(node, context);
        }

        private boolean isConstant(Expression expression)
        {
            return expression instanceof Constant;
        }
    }
}
