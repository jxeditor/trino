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
package io.trino.sql.ir;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.graph.SuccessorsFunction;
import com.google.common.graph.Traverser;
import io.trino.Session;
import io.trino.metadata.Metadata;
import io.trino.spi.type.Type;
import io.trino.sql.PlannerContext;
import io.trino.sql.planner.DeterminismEvaluator;
import io.trino.sql.planner.IrExpressionInterpreter;
import io.trino.sql.planner.IrTypeAnalyzer;
import io.trino.sql.planner.LiteralEncoder;
import io.trino.sql.planner.NoOpSymbolResolver;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.SymbolsExtractor;
import io.trino.sql.planner.TypeProvider;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static io.trino.sql.ir.BooleanLiteral.FALSE_LITERAL;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public final class IrUtils
{
    private IrUtils() {}

    public static List<Expression> extractConjuncts(Expression expression)
    {
        return extractPredicates(LogicalExpression.Operator.AND, expression);
    }

    public static List<Expression> extractDisjuncts(Expression expression)
    {
        return extractPredicates(LogicalExpression.Operator.OR, expression);
    }

    public static List<Expression> extractPredicates(LogicalExpression expression)
    {
        return extractPredicates(expression.getOperator(), expression);
    }

    public static List<Expression> extractPredicates(LogicalExpression.Operator operator, Expression expression)
    {
        ImmutableList.Builder<Expression> resultBuilder = ImmutableList.builder();
        extractPredicates(operator, expression, resultBuilder);
        return resultBuilder.build();
    }

    private static void extractPredicates(LogicalExpression.Operator operator, Expression expression, ImmutableList.Builder<Expression> resultBuilder)
    {
        if (expression instanceof LogicalExpression logicalExpression && logicalExpression.getOperator() == operator) {
            for (Expression term : logicalExpression.getTerms()) {
                extractPredicates(operator, term, resultBuilder);
            }
        }
        else {
            resultBuilder.add(expression);
        }
    }

    public static Expression and(Expression... expressions)
    {
        return and(Arrays.asList(expressions));
    }

    public static Expression and(Collection<Expression> expressions)
    {
        return logicalExpression(LogicalExpression.Operator.AND, expressions);
    }

    public static Expression or(Expression... expressions)
    {
        return or(Arrays.asList(expressions));
    }

    public static Expression or(Collection<Expression> expressions)
    {
        return logicalExpression(LogicalExpression.Operator.OR, expressions);
    }

    public static Expression logicalExpression(LogicalExpression.Operator operator, Collection<Expression> expressions)
    {
        requireNonNull(operator, "operator is null");
        requireNonNull(expressions, "expressions is null");

        if (expressions.isEmpty()) {
            switch (operator) {
                case AND:
                    return TRUE_LITERAL;
                case OR:
                    return FALSE_LITERAL;
            }
            throw new IllegalArgumentException("Unsupported LogicalExpression operator");
        }

        if (expressions.size() == 1) {
            return Iterables.getOnlyElement(expressions);
        }

        return new LogicalExpression(operator, ImmutableList.copyOf(expressions));
    }

    public static Expression combinePredicates(Metadata metadata, LogicalExpression.Operator operator, Expression... expressions)
    {
        return combinePredicates(metadata, operator, Arrays.asList(expressions));
    }

    public static Expression combinePredicates(Metadata metadata, LogicalExpression.Operator operator, Collection<Expression> expressions)
    {
        if (operator == LogicalExpression.Operator.AND) {
            return combineConjuncts(metadata, expressions);
        }

        return combineDisjuncts(metadata, expressions);
    }

    public static Expression combineConjuncts(Metadata metadata, Expression... expressions)
    {
        return combineConjuncts(metadata, Arrays.asList(expressions));
    }

    public static Expression combineConjuncts(Metadata metadata, Collection<Expression> expressions)
    {
        requireNonNull(expressions, "expressions is null");

        List<Expression> conjuncts = expressions.stream()
                .flatMap(e -> extractConjuncts(e).stream())
                .filter(e -> !e.equals(TRUE_LITERAL))
                .collect(toList());

        conjuncts = removeDuplicates(metadata, conjuncts);

        if (conjuncts.contains(FALSE_LITERAL)) {
            return FALSE_LITERAL;
        }

        return and(conjuncts);
    }

    public static Expression combineConjunctsWithDuplicates(Collection<Expression> expressions)
    {
        requireNonNull(expressions, "expressions is null");

        List<Expression> conjuncts = expressions.stream()
                .flatMap(e -> extractConjuncts(e).stream())
                .filter(e -> !e.equals(TRUE_LITERAL))
                .collect(toList());

        if (conjuncts.contains(FALSE_LITERAL)) {
            return FALSE_LITERAL;
        }

        return and(conjuncts);
    }

    public static Expression combineDisjuncts(Metadata metadata, Expression... expressions)
    {
        return combineDisjuncts(metadata, Arrays.asList(expressions));
    }

    public static Expression combineDisjuncts(Metadata metadata, Collection<Expression> expressions)
    {
        return combineDisjunctsWithDefault(metadata, expressions, FALSE_LITERAL);
    }

    public static Expression combineDisjunctsWithDefault(Metadata metadata, Collection<Expression> expressions, Expression emptyDefault)
    {
        requireNonNull(expressions, "expressions is null");

        List<Expression> disjuncts = expressions.stream()
                .flatMap(e -> extractDisjuncts(e).stream())
                .filter(e -> !e.equals(FALSE_LITERAL))
                .collect(toList());

        disjuncts = removeDuplicates(metadata, disjuncts);

        if (disjuncts.contains(TRUE_LITERAL)) {
            return TRUE_LITERAL;
        }

        return disjuncts.isEmpty() ? emptyDefault : or(disjuncts);
    }

    public static Expression filterDeterministicConjuncts(Metadata metadata, Expression expression)
    {
        return filterConjuncts(metadata, expression, expression1 -> DeterminismEvaluator.isDeterministic(expression1, metadata));
    }

    public static Expression filterNonDeterministicConjuncts(Metadata metadata, Expression expression)
    {
        return filterConjuncts(metadata, expression, not(testExpression -> DeterminismEvaluator.isDeterministic(testExpression, metadata)));
    }

    public static Expression filterConjuncts(Metadata metadata, Expression expression, Predicate<Expression> predicate)
    {
        List<Expression> conjuncts = extractConjuncts(expression).stream()
                .filter(predicate)
                .collect(toList());

        return combineConjuncts(metadata, conjuncts);
    }

    @SafeVarargs
    public static Function<Expression, Expression> expressionOrNullSymbols(Predicate<Symbol>... nullSymbolScopes)
    {
        return expression -> {
            ImmutableList.Builder<Expression> resultDisjunct = ImmutableList.builder();
            resultDisjunct.add(expression);

            for (Predicate<Symbol> nullSymbolScope : nullSymbolScopes) {
                List<Symbol> symbols = SymbolsExtractor.extractUnique(expression).stream()
                        .filter(nullSymbolScope)
                        .collect(toImmutableList());

                if (symbols.isEmpty()) {
                    continue;
                }

                ImmutableList.Builder<Expression> nullConjuncts = ImmutableList.builder();
                for (Symbol symbol : symbols) {
                    nullConjuncts.add(new IsNullPredicate(symbol.toSymbolReference()));
                }

                resultDisjunct.add(and(nullConjuncts.build()));
            }

            return or(resultDisjunct.build());
        };
    }

    /**
     * Returns whether expression is effectively literal. An effectively literal expression is a simple constant value, or null,
     * in either {@link Literal} form, or other form returned by {@link LiteralEncoder}. In particular, other constant expressions
     * like a deterministic function call with constant arguments are not considered effectively literal.
     */
    public static boolean isEffectivelyLiteral(PlannerContext plannerContext, Session session, Expression expression)
    {
        if (expression instanceof Literal) {
            return true;
        }
        if (expression instanceof Cast) {
            return ((Cast) expression).getExpression() instanceof Literal
                    // a Cast(Literal(...)) can fail, so this requires verification
                    && constantExpressionEvaluatesSuccessfully(plannerContext, session, expression);
        }

        return false;
    }

    private static boolean constantExpressionEvaluatesSuccessfully(PlannerContext plannerContext, Session session, Expression constantExpression)
    {
        Map<NodeRef<Expression>, Type> types = new IrTypeAnalyzer(plannerContext).getTypes(session, TypeProvider.empty(), constantExpression);
        IrExpressionInterpreter interpreter = new IrExpressionInterpreter(constantExpression, plannerContext, session, types);
        Object literalValue = interpreter.optimize(NoOpSymbolResolver.INSTANCE);
        return !(literalValue instanceof Expression);
    }

    /**
     * Removes duplicate deterministic expressions. Preserves the relative order
     * of the expressions in the list.
     */
    private static List<Expression> removeDuplicates(Metadata metadata, List<Expression> expressions)
    {
        Set<Expression> seen = new HashSet<>();

        ImmutableList.Builder<Expression> result = ImmutableList.builder();
        for (Expression expression : expressions) {
            if (!DeterminismEvaluator.isDeterministic(expression, metadata)) {
                result.add(expression);
            }
            else if (!seen.contains(expression)) {
                result.add(expression);
                seen.add(expression);
            }
        }

        return result.build();
    }

    public static Stream<Expression> preOrder(Expression node)
    {
        return stream(
                Traverser.forTree((SuccessorsFunction<Expression>) Expression::getChildren)
                        .depthFirstPreOrder(requireNonNull(node, "node is null")));
    }

    /**
     * <p>Compares two AST trees recursively by applying the provided comparator to each pair of nodes.</p>
     *
     * <p>The comparator can perform a hybrid shallow/deep comparison. If it returns true or false, the
     * nodes and any subtrees are considered equal or different, respectively. If it returns null,
     * the nodes are considered shallowly-equal and their children will be compared recursively.</p>
     */
    public static boolean treeEqual(Expression left, Expression right, BiFunction<Expression, Expression, Boolean> subtreeComparator)
    {
        Boolean equal = subtreeComparator.apply(left, right);

        if (equal != null) {
            return equal;
        }

        List<? extends Expression> leftChildren = left.getChildren();
        List<? extends Expression> rightChildren = right.getChildren();

        if (leftChildren.size() != rightChildren.size()) {
            return false;
        }

        for (int i = 0; i < leftChildren.size(); i++) {
            if (!treeEqual(leftChildren.get(i), rightChildren.get(i), subtreeComparator)) {
                return false;
            }
        }

        return true;
    }

    /**
     * <p>Computes a hash of the given AST by applying the provided subtree hasher at each level.</p>
     *
     * <p>If the hasher returns a non-empty {@link OptionalInt}, the value is treated as the hash for
     * the subtree at that node. Otherwise, the hashes of its children are computed and combined.</p>
     */
    public static int treeHash(Expression node, Function<Expression, OptionalInt> subtreeHasher)
    {
        OptionalInt hash = subtreeHasher.apply(node);

        if (hash.isPresent()) {
            return hash.getAsInt();
        }

        List<? extends Expression> children = node.getChildren();

        int result = node.getClass().hashCode();
        for (Expression element : children) {
            result = 31 * result + treeHash(element, subtreeHasher);
        }

        return result;
    }
}
